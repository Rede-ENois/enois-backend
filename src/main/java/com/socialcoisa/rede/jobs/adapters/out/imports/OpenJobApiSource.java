package com.socialcoisa.rede.jobs.adapters.out.imports;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.util.StringUtils;

import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.jobs.application.port.out.JobImportSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fonte de vagas por API PUBLICA (sem chave), configuravel por uma {@link Spec}. Serve agregadores
 * que permitem LISTAR e REDIRECIONAR para a vaga original (ex.: Remotive, Jobicy, RemoteOK,
 * Himalayas). O que a fonte nao fornece fica nulo — nada e inventado.
 *
 * Nao e scraping: sao as APIs oficiais dos agregadores. O credito/link de volta e feito pelo nome da
 * plataforma (selo "Vaga externa · {plataforma}") + a URL da vaga, como as fontes pedem.
 */
public class OpenJobApiSource implements JobImportSource {

	private final Spec spec;
	private final ObjectMapper objectMapper;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public OpenJobApiSource(Spec spec, ObjectMapper objectMapper) {
		this.spec = spec;
		this.objectMapper = objectMapper;
	}

	/** Como ler o JSON de uma API publica: onde estao a lista e cada campo. */
	public record Spec(String name, String url, boolean rootIsArray, String rootPath, String idPath,
			String titlePath, String companyPath, String locationPath, String salaryTextPath, String salaryMinPath,
			String salaryMaxPath, String currencyPath, String typePath, String urlPath, String applyPath,
			String publishedAtPath, boolean publishedAtEpochSeconds, String dateFormat, String attribution,
			String descriptionPath) {
	}

	@Override
	public String name() {
		return spec.name();
	}

	@Override
	public boolean available() {
		return true;
	}

	@Override
	public List<ExternalJob> discover(int limit) {
		JsonNode body = fetch();
		JsonNode list = spec.rootIsArray() ? body
			: (StringUtils.hasText(spec.rootPath()) ? body.path(spec.rootPath()) : body);
		List<ExternalJob> jobs = new ArrayList<>();
		if (!list.isArray()) {
			return jobs;
		}
		for (JsonNode node : list) {
			if (limit > 0 && jobs.size() >= limit) {
				break;
			}
			String id = value(node, spec.idPath());
			String title = value(node, spec.titlePath());
			String url = value(node, spec.urlPath());
			// Sem titulo/url nao da para mostrar/redirecionar (e algumas APIs misturam avisos na lista).
			if (!StringUtils.hasText(title) || !StringUtils.hasText(url)
					|| (spec.rootIsArray() && !StringUtils.hasText(id))) {
				continue;
			}
			String apply = value(node, spec.applyPath());
			String description = value(node, spec.descriptionPath());
			jobs.add(new ExternalJob(StringUtils.hasText(id) ? id : stableId(url), title,
				value(node, spec.companyPath()), value(node, spec.locationPath()), description,
				salary(node), value(node, spec.typePath()), url,
				StringUtils.hasText(apply) ? apply : url, spec.name(), publishedAt(node)));
		}
		return jobs;
	}

	private JsonNode fetch() {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(spec.url()))
				.timeout(Duration.ofSeconds(25))
				.header("User-Agent", "EnoisBot/1.0 (+https://enois.ia.br/developers)")
				.header("Accept", "application/json")
				.GET()
				.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				throw new ApiException(502, "IMPORT_SOURCE_ERROR", "Fonte externa indisponivel agora.");
			}
			return objectMapper.readTree(response.body());
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new ApiException(502, "IMPORT_SOURCE_ERROR", "Consulta a fonte externa interrompida.");
		}
		catch (ApiException apiException) {
			throw apiException;
		}
		catch (Exception failure) {
			throw new ApiException(502, "IMPORT_SOURCE_ERROR", "Falha ao consultar a fonte externa.");
		}
	}

	/** Faixa textual, ou montada de min/max + moeda. Sem dados -> nulo (nao inventa). */
	String salary(JsonNode node) {
		String text = value(node, spec.salaryTextPath());
		if (StringUtils.hasText(text)) {
			return text;
		}
		// Alguns agregadores usam "0" quando nao informam a faixa (RemoteOK): trata como ausente.
		String min = blankIfZero(value(node, spec.salaryMinPath()));
		String max = blankIfZero(value(node, spec.salaryMaxPath()));
		if (!StringUtils.hasText(min) && !StringUtils.hasText(max)) {
			return null;
		}
		String currency = value(node, spec.currencyPath());
		String prefix = StringUtils.hasText(currency) ? currency + " " : "";
		if (StringUtils.hasText(min) && StringUtils.hasText(max)) {
			return prefix + min + " - " + max;
		}
		return prefix + (StringUtils.hasText(min) ? min : max);
	}

	Instant publishedAt(JsonNode node) {
		String raw = value(node, spec.publishedAtPath());
		if (!StringUtils.hasText(raw)) {
			return null;
		}
		try {
			if (spec.publishedAtEpochSeconds()) {
				long seconds = Long.parseLong(raw.trim());
				return seconds > 100_000_000_000L ? Instant.ofEpochMilli(seconds) : Instant.ofEpochSecond(seconds);
			}
			if (spec.dateFormat() != null) {
				return java.time.LocalDate.parse(raw.trim(),
					java.time.format.DateTimeFormatter.ofPattern(spec.dateFormat()))
					.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
			}
			return Instant.parse(raw.trim().length() == 10 ? raw.trim() + "T00:00:00Z" : raw.trim());
		}
		catch (DateTimeParseException | NumberFormatException badDate) {
			return null;
		}
	}

	/** Le "a.b", "a[0]" ou "a[0].b"; array vira o primeiro elemento. Ausente -> nulo. */
	static String value(JsonNode node, String path) {
		if (node == null || path == null || path.isBlank()) {
			return null;
		}
		JsonNode current = node;
		for (String part : path.split("\\.")) {
			int index = -1;
			String key = part;
			int bracket = part.indexOf('[');
			if (bracket >= 0) {
				key = part.substring(0, bracket);
				index = Integer.parseInt(part.substring(bracket + 1, part.indexOf(']')));
			}
			if (!key.isEmpty()) {
				current = current.path(key);
			}
			if (index >= 0) {
				current = current.isArray() && current.size() > index ? current.get(index) : current.path(index);
			}
			if (current.isMissingNode() || current.isNull()) {
				return null;
			}
		}
		String text = current.isValueNode() ? current.asString() : current.toString();
		return StringUtils.hasText(text) ? text.trim() : null;
	}

	private static String blankIfZero(String value) {
		return "0".equals(value) || "0.0".equals(value) ? null : value;
	}

	private static String stableId(String url) {
		return Integer.toHexString(url.hashCode());
	}
}
