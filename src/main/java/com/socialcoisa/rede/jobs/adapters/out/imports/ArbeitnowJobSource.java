package com.socialcoisa.rede.jobs.adapters.out.imports;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.jobs.application.port.out.JobImportSource;
import com.socialcoisa.rede.shared.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Arbeitnow — API pública de vagas (majoritariamente Alemanha/Europa, com remotas internacionais).
 *
 * A própria API declara os termos na resposta: "free public API for jobs, please do not abuse. I
 * would appreciate linking back to the site." Por isso: paginação limitada por configuração, uma
 * passada por ciclo, e a URL da vaga aponta para o Arbeitnow (o link de volta).
 *
 * A API não tem busca por país nem filtro de data: o que ela devolve é o que existe. Nada é
 * inventado — `remote` é booleano na fonte, `job_types` é texto livre alemão/inglês.
 */
@Component
public class ArbeitnowJobSource implements JobImportSource {

	private static final String DEFAULT_URL = "https://www.arbeitnow.com/api/job-board-api";

	private final String baseUrl;
	private final boolean enabled;
	/** Teto de páginas por ciclo: a API pede para não abusar, e 300 itens por página já é muito. */
	private final int maxPages;
	private final ObjectMapper objectMapper;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public ArbeitnowJobSource(
			@Value("${rede.jobs.import.arbeitnow.url:" + DEFAULT_URL + "}") String baseUrl,
			@Value("${rede.jobs.import.arbeitnow.enabled:true}") boolean enabled,
			@Value("${rede.jobs.import.arbeitnow.max-pages:3}") int maxPages,
			ObjectMapper objectMapper) {
		this.baseUrl = baseUrl;
		this.enabled = enabled;
		this.maxPages = Math.max(1, maxPages);
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "Arbeitnow";
	}

	@Override
	public boolean available() {
		return enabled;
	}

	@Override
	public List<ExternalJob> discover(int limit) {
		int cap = limit <= 0 ? Integer.MAX_VALUE : limit;
		List<ExternalJob> jobs = new ArrayList<>();
		for (int page = 1; page <= maxPages && jobs.size() < cap; page++) {
			JsonNode body = fetch(page);
			JsonNode data = body.path("data");
			if (!data.isArray() || data.isEmpty()) {
				break;
			}
			for (JsonNode node : data) {
				if (jobs.size() >= cap) {
					break;
				}
				ExternalJob job = toJob(node);
				if (job != null) {
					jobs.add(job);
				}
			}
			// Sem próxima página, parar: não insistir numa URL que a fonte não oferece.
			if (body.path("links").path("next").isNull() || body.path("links").path("next").isMissingNode()) {
				break;
			}
		}
		return jobs;
	}

	/** Contrato offline: mapeia um corpo já capturado (usado nos testes). */
	List<ExternalJob> parse(String json, int limit) {
		int cap = limit <= 0 ? Integer.MAX_VALUE : limit;
		JsonNode data = objectMapper.readTree(json).path("data");
		List<ExternalJob> jobs = new ArrayList<>();
		if (!data.isArray()) {
			return jobs;
		}
		for (JsonNode node : data) {
			if (jobs.size() >= cap) {
				break;
			}
			ExternalJob job = toJob(node);
			if (job != null) {
				jobs.add(job);
			}
		}
		return jobs;
	}

	private JsonNode fetch(int page) {		try {
			String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "page=" + page;
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(25))
				.header("User-Agent", "EnoisBot/1.0 (+https://enois.ia.br/developers)")
				.header("Accept", "application/json")
				.GET()
				.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				throw new ApiException(502, "IMPORT_SOURCE_ERROR", "Arbeitnow indisponível agora.");
			}
			return objectMapper.readTree(response.body());
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new ApiException(502, "IMPORT_SOURCE_ERROR", "Consulta ao Arbeitnow interrompida.");
		}
		catch (ApiException apiException) {
			throw apiException;
		}
		catch (Exception failure) {
			throw new ApiException(502, "IMPORT_SOURCE_ERROR", "Falha ao consultar o Arbeitnow.");
		}
	}

	/** Uma vaga do JSON. Sem título/URL/slug não dá para listar nem deduplicar: ignora. */
	ExternalJob toJob(JsonNode node) {
		String slug = text(node, "slug");
		String title = text(node, "title");
		String url = text(node, "url");
		if (!StringUtils.hasText(title) || !StringUtils.hasText(url)) {
			return null;
		}
		String externalId = StringUtils.hasText(slug) ? slug : Integer.toHexString(url.hashCode());
		String location = text(node, "location");
		boolean remote = node.path("remote").asBoolean(false);
		// `job_types` mistura vínculo e senioridade em alemão ("berufserfahren" = experiente):
		// a taxonomia só reconhece o que é inequívoco, o resto fica nulo.
		String jobTypes = join(node.path("job_types"));
		String tags = join(node.path("tags"));
		Instant publishedAt = epoch(node.path("created_at"));
		return new ExternalJob(externalId, title, text(node, "company_name"), location,
			text(node, "description"), null, jobTypes, url, url, name(), publishedAt,
			new Meta(com.socialcoisa.rede.jobs.domain.JobTaxonomy.level(jobTypes),
				remote ? "REMOTE" : null, null, null, null, null, null, null, null, url, null));
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.asString();
		return StringUtils.hasText(text) ? text.trim() : null;
	}

	private static String join(JsonNode array) {
		if (!array.isArray() || array.isEmpty()) {
			return null;
		}
		List<String> values = new ArrayList<>();
		for (JsonNode item : array) {
			String value = item.asString();
			if (StringUtils.hasText(value)) {
				values.add(value.trim());
			}
		}
		return values.isEmpty() ? null : String.join(", ", values);
	}

	private static Instant epoch(JsonNode node) {
		if (node.isMissingNode() || node.isNull()) {
			return null;
		}
		long seconds = node.asLong(0);
		if (seconds <= 0) {
			return null;
		}
		return seconds > 100_000_000_000L ? Instant.ofEpochMilli(seconds) : Instant.ofEpochSecond(seconds);
	}
}
