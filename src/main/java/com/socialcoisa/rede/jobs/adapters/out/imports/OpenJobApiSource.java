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
			String descriptionPath, String defaultWorkModel, boolean locationIsRegion, MetaPaths meta) {

		/** Fonte que só entrega o básico: nenhum caminho de metadado estruturado. */
		public Spec(String name, String url, boolean rootIsArray, String rootPath, String idPath,
				String titlePath, String companyPath, String locationPath, String salaryTextPath,
				String salaryMinPath, String salaryMaxPath, String currencyPath, String typePath, String urlPath,
				String applyPath, String publishedAtPath, boolean publishedAtEpochSeconds, String dateFormat,
				String attribution, String descriptionPath, String defaultWorkModel, boolean locationIsRegion) {
			this(name, url, rootIsArray, rootPath, idPath, titlePath, companyPath, locationPath, salaryTextPath,
				salaryMinPath, salaryMaxPath, currencyPath, typePath, urlPath, applyPath, publishedAtPath,
				publishedAtEpochSeconds, dateFormat, attribution, descriptionPath, defaultWorkModel,
				locationIsRegion, MetaPaths.empty());
		}
	}

	/**
	 * Caminhos dos metadados estruturados. Todos opcionais: caminho nulo = a fonte não informa
	 * aquele campo, e ele fica nulo na vaga (nada é inferido).
	 */
	public record MetaPaths(String levelPath, String workModelPath, String cityPath, String statePath,
			String countryPath, String salaryPeriodPath, String expiresAtPath) {

		public static MetaPaths empty() {
			return new MetaPaths(null, null, null, null, null, null, null);
		}
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
		return parse(fetch(), limit);
	}

	/** Mapeamento da resposta ja baixada. Separado do fetch para o teste cobrir so o mapeamento. */
	List<ExternalJob> parse(JsonNode body, int limit) {
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
				StringUtils.hasText(apply) ? apply : url, spec.name(), publishedAt(node), meta(node)));
		}
		return jobs;
	}

	/**
	 * Metadados estruturados da vaga. O que a fonte não informa fica nulo; o que ela informa em
	 * texto livre (senioridade, modelo, vínculo) passa pela taxonomia, que só traduz sinônimos.
	 */
	private JobImportSource.Meta meta(JsonNode node) {
		MetaPaths paths = spec.meta() == null ? MetaPaths.empty() : spec.meta();
		String location = value(node, spec.locationPath());
		String city = value(node, paths.cityPath());
		String state = value(node, paths.statePath());
		String country = value(node, paths.countryPath());
		// Sem campos separados, a localidade inteira vira `city` só quando é um único lugar —
		// "São Paulo, SP" não é cidade, e gravar assim faria o filtro de cidade errar. Quando a fonte
		// declara o país, o texto da localidade é o país ("USA"), não uma cidade: aí não há fallback.
		// E quando a fonte diz que aquele campo é a REGIÃO aceita (Jobicy `jobGeo`, RemoteOK
		// `location`), ele nunca é cidade — é onde a pessoa pode morar, não onde a vaga fica.
		if (city == null && country == null && !spec.locationIsRegion() && location != null
				&& !location.contains(",") && !location.contains("/")) {
			city = location;
		}
		// Modelo de trabalho: o campo da fonte, senão o texto da localidade, senão o que a própria
		// fonte declara sobre si (agregador de vagas remotas só publica vaga remota). Sem nada disso
		// fica nulo — nunca inferido do título.
		String workModel = com.socialcoisa.rede.jobs.domain.JobTaxonomy.workModel(
			firstNonBlank(value(node, paths.workModelPath()), location));
		if (workModel == null) {
			workModel = spec.defaultWorkModel();
		}
		return new JobImportSource.Meta(
			com.socialcoisa.rede.jobs.domain.JobTaxonomy.level(value(node, paths.levelPath())),
			workModel,
			city, state, country,
			amount(value(node, spec.salaryMinPath())), amount(value(node, spec.salaryMaxPath())),
			value(node, spec.currencyPath()),
			com.socialcoisa.rede.jobs.domain.JobTaxonomy.salaryPeriod(value(node, paths.salaryPeriodPath())),
			spec.attribution(), expiresAt(node, paths));
	}

	private static String firstNonBlank(String first, String second) {
		return StringUtils.hasText(first) ? first : second;
	}

	/** Valor numérico da faixa; "0" (usado por algumas APIs para "não informado") vira nulo. */
	private static java.math.BigDecimal amount(String raw) {
		if (!StringUtils.hasText(raw) || "0".equals(raw) || "0.0".equals(raw)) {
			return null;
		}
		try {
			return new java.math.BigDecimal(raw.replaceAll("[^0-9.]", ""));
		}
		catch (NumberFormatException notANumber) {
			return null;
		}
	}

	private Instant expiresAt(JsonNode node, MetaPaths paths) {
		String raw = value(node, paths.expiresAtPath());
		// Algumas fontes publicam o prazo em epoch de segundos (Himalayas `expiryDate`), outras em
		// ISO. So digitos e epoch; o resto passa pelo parser de data.
		if (raw != null && raw.trim().matches("\\d{9,}")) {
			return Instant.ofEpochSecond(Long.parseLong(raw.trim()));
		}
		return instant(raw);
	}

	/**
	 * Data da fonte em UTC. Algumas APIs publicam "2026-11-08T21:47:58" SEM fuso (Remotive): isso
	 * não é {@link Instant#parse}, que exige offset — antes disso a data simplesmente se perdia e a
	 * vaga ficava sem publicação, quebrando a ordenação e o filtro por data.
	 */
	static Instant instant(String raw) {
		if (!StringUtils.hasText(raw)) {
			return null;
		}
		String text = raw.trim();
		try {
			return text.length() == 10 ? Instant.parse(text + "T00:00:00Z") : Instant.parse(text);
		}
		catch (DateTimeParseException noOffset) {
			try {
				return java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC);
			}
			catch (DateTimeParseException unparseable) {
				return null;
			}
		}
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
			return instant(raw);
		}
		catch (NumberFormatException badNumber) {
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
