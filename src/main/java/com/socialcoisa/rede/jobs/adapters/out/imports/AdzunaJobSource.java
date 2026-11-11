package com.socialcoisa.rede.jobs.adapters.out.imports;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * Adzuna — agregador com API oficial, a única fonte paga considerada.
 *
 * <p><b>Fica desligada sem credencial.</b> O plano gratuito da Adzuna é de AVALIAÇÃO: uso comercial
 * exige licença paga. Por isso a fonte só liga com `app-id`/`app-key` configurados e
 * `enabled=true` — e o operador precisa ter aceitado os termos no cadastro. Sem isso, `available()`
 * devolve falso e o ciclo a ignora.
 *
 * <p>Atribuição: os termos exigem o crédito "The Adzuna API" com link. O crédito é feito pelo nome
 * da plataforma no selo da vaga + a URL de atribuição gravada em `attributionUrl`.
 *
 * <p>O país é configurável (`br` por padrão). A Adzuna não publica a lista de países na
 * documentação aberta; se o código não for aceito, a fonte falha no ciclo e o operador vê o erro —
 * não há fallback silencioso para outro país.
 */
@Component
public class AdzunaJobSource implements JobImportSource {

	private static final String DEFAULT_BASE = "https://api.adzuna.com/v1/api/jobs";

	private final String baseUrl;
	private final String country;
	private final String appId;
	private final String appKey;
	private final boolean enabled;
	private final int maxPages;
	private final ObjectMapper objectMapper;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public AdzunaJobSource(
			@Value("${rede.jobs.import.adzuna.base-url:" + DEFAULT_BASE + "}") String baseUrl,
			@Value("${rede.jobs.import.adzuna.country:br}") String country,
			@Value("${rede.jobs.import.adzuna.app-id:}") String appId,
			@Value("${rede.jobs.import.adzuna.app-key:}") String appKey,
			@Value("${rede.jobs.import.adzuna.enabled:false}") boolean enabled,
			@Value("${rede.jobs.import.adzuna.max-pages:2}") int maxPages,
			ObjectMapper objectMapper) {
		this.baseUrl = baseUrl;
		this.country = country;
		this.appId = appId;
		this.appKey = appKey;
		this.enabled = enabled;
		this.maxPages = Math.max(1, maxPages);
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "Adzuna";
	}

	/** Sem credencial não há fonte: o plano gratuito é de avaliação e exige cadastro. */
	@Override
	public boolean available() {
		return enabled && StringUtils.hasText(appId) && StringUtils.hasText(appKey);
	}

	@Override
	public List<ExternalJob> discover(int limit) {
		int cap = limit <= 0 ? Integer.MAX_VALUE : limit;
		List<ExternalJob> jobs = new ArrayList<>();
		for (int page = 1; page <= maxPages && jobs.size() < cap; page++) {
			JsonNode body = fetch(page);
			JsonNode results = body.path("results");
			if (!results.isArray() || results.isEmpty()) {
				break;
			}
			for (JsonNode node : results) {
				if (jobs.size() >= cap) {
					break;
				}
				ExternalJob job = toJob(node);
				if (job != null) {
					jobs.add(job);
				}
			}
		}
		return jobs;
	}

	/** Contrato offline: mapeia um corpo já capturado (usado nos testes). */
	List<ExternalJob> parse(String json, int limit) {
		int cap = limit <= 0 ? Integer.MAX_VALUE : limit;
		JsonNode results = objectMapper.readTree(json).path("results");
		List<ExternalJob> jobs = new ArrayList<>();
		if (!results.isArray()) {
			return jobs;
		}
		for (JsonNode node : results) {
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

	private JsonNode fetch(int page) {
		try {
			String url = baseUrl + "/" + country + "/search/" + page
				+ "?app_id=" + encode(appId) + "&app_key=" + encode(appKey)
				+ "&results_per_page=50&content-type=application/json";
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(25))
				.header("User-Agent", "EnoisBot/1.0 (+https://enois.ia.br/developers)")
				.header("Accept", "application/json")
				.GET()
				.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 401 || response.statusCode() == 403) {
				throw new ApiException(502, "IMPORT_SOURCE_ERROR",
					"Adzuna recusou as credenciais configuradas.");
			}
			if (response.statusCode() / 100 != 2) {
				throw new ApiException(502, "IMPORT_SOURCE_ERROR", "Adzuna indisponível agora.");
			}
			return objectMapper.readTree(response.body());
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new ApiException(502, "IMPORT_SOURCE_ERROR", "Consulta à Adzuna interrompida.");
		}
		catch (ApiException apiException) {
			throw apiException;
		}
		catch (Exception failure) {
			throw new ApiException(502, "IMPORT_SOURCE_ERROR", "Falha ao consultar a Adzuna.");
		}
	}

	ExternalJob toJob(JsonNode node) {
		String id = text(node, "id");
		String title = text(node, "title");
		String url = text(node, "redirect_url");
		if (!StringUtils.hasText(title) || !StringUtils.hasText(url)) {
			return null;
		}
		String company = text(node.path("company"), "display_name");
		String location = text(node.path("location"), "display_name");
		BigDecimal min = amount(node.path("salary_min"));
		BigDecimal max = amount(node.path("salary_max"));
		String contractTime = text(node, "contract_time");
		String contractType = text(node, "contract_type");
		Instant publishedAt = instant(node, "created");
		return new ExternalJob(StringUtils.hasText(id) ? id : Integer.toHexString(url.hashCode()), title,
			company, location, text(node, "description"), null,
			firstNonBlank(contractType, contractTime), url, url, name(), publishedAt,
			new Meta(null, null, null, null, null, min, max, null, null, "https://www.adzuna.com", null));
	}

	private static String encode(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.asString();
		return StringUtils.hasText(text) ? text.trim() : null;
	}

	private static BigDecimal amount(JsonNode node) {
		if (node.isMissingNode() || node.isNull() || !node.isNumber()) {
			return null;
		}
		return node.decimalValue();
	}

	private static Instant instant(JsonNode node, String field) {
		String raw = text(node, field);
		if (raw == null) {
			return null;
		}
		try {
			return Instant.parse(raw);
		}
		catch (RuntimeException badDate) {
			return null;
		}
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
	}
}
