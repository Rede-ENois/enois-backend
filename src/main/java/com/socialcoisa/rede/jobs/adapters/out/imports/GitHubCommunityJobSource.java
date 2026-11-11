package com.socialcoisa.rede.jobs.adapters.out.imports;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.jobs.application.port.out.JobImportSource;
import com.socialcoisa.rede.shared.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Vagas das comunidades brasileiras no GitHub (backend-br/vagas, frontendbr/vagas).
 *
 * É a fonte mais adequada ao mercado brasileiro: as vagas são publicadas como issues, com seções
 * fixas no corpo (`### Nível`, `### Regime de contratação`, `### Modelo de trabalho`,
 * `### Localização`, `### Como se candidatar`) e labels estruturadas. O repositório é MIT e a API
 * de issues é pública — nada de scraping.
 *
 * Limites respeitados: a API sem token permite 60 requisições/hora, então a fonte pede UMA página
 * por ciclo (configurável) e usa `If-None-Match` quando o ETag é conhecido. Com token, o limite
 * sobe e a fonte continua funcionando igual.
 *
 * O corpo da issue é Markdown escrito por pessoas: o parser lê só as seções que existem e deixa
 * nulo o que não estiver lá. Nunca deduzir senioridade pelo título.
 */
@Component
public class GitHubCommunityJobSource implements JobImportSource {

	/** Seções do template de issue, em minúsculas, como aparecem no corpo. */
	private static final Pattern SECTION = Pattern.compile("^#{2,4}\\s*(.+?)\\s*$", Pattern.MULTILINE);

	private final String repo;
	private final String displayName;
	private final boolean enabled;
	private final int perPage;
	private final String token;
	private final ObjectMapper objectMapper;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public GitHubCommunityJobSource(
			@Value("${rede.jobs.import.github-community.repo:backend-br/vagas}") String repo,
			@Value("${rede.jobs.import.github-community.name:GitHub · backend-br}") String displayName,
			@Value("${rede.jobs.import.github-community.enabled:true}") boolean enabled,
			@Value("${rede.jobs.import.github-community.per-page:100}") int perPage,
			@Value("${rede.jobs.import.github-community.token:}") String token,
			ObjectMapper objectMapper) {
		this.repo = repo;
		this.displayName = displayName;
		this.enabled = enabled;
		this.perPage = Math.clamp(perPage, 1, 100);
		this.token = token;
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return displayName;
	}

	@Override
	public boolean available() {
		return enabled;
	}

	@Override
	public List<ExternalJob> discover(int limit) {
		int cap = limit <= 0 ? Integer.MAX_VALUE : limit;
		JsonNode issues = fetch();
		List<ExternalJob> jobs = new ArrayList<>();
		if (!issues.isArray()) {
			return jobs;
		}
		for (JsonNode issue : issues) {
			if (jobs.size() >= cap) {
				break;
			}
			// Pull requests aparecem na listagem de issues da API; não são vagas.
			if (issue.path("pull_request").isObject()) {
				continue;
			}
			ExternalJob job = toJob(issue);
			if (job != null) {
				jobs.add(job);
			}
		}
		return jobs;
	}

	/** Contrato offline: mapeia um corpo já capturado (usado nos testes). */
	List<ExternalJob> parse(String json, int limit) {
		int cap = limit <= 0 ? Integer.MAX_VALUE : limit;
		JsonNode issues = objectMapper.readTree(json);
		List<ExternalJob> jobs = new ArrayList<>();
		if (!issues.isArray()) {
			return jobs;
		}
		for (JsonNode issue : issues) {
			if (jobs.size() >= cap) {
				break;
			}
			if (issue.path("pull_request").isObject()) {
				continue;
			}
			ExternalJob job = toJob(issue);
			if (job != null) {
				jobs.add(job);
			}
		}
		return jobs;
	}

	private JsonNode fetch() {
		try {
			String url = "https://api.github.com/repos/" + repo + "/issues?state=open&sort=created"
				+ "&direction=desc&per_page=" + perPage;
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(25))
				.header("User-Agent", "EnoisBot/1.0 (+https://enois.ia.br/developers)")
				.header("Accept", "application/vnd.github+json")
				.header("X-GitHub-Api-Version", "2022-11-28")
				.GET();
			if (StringUtils.hasText(token)) {
				builder.header("Authorization", "Bearer " + token);
			}
			HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 403 || response.statusCode() == 429) {
				// Rate limit: não insistir. O ciclo seguinte tenta de novo.
				throw new ApiException(502, "IMPORT_SOURCE_ERROR", "Limite de consultas do GitHub atingido.");
			}
			if (response.statusCode() / 100 != 2) {
				throw new ApiException(502, "IMPORT_SOURCE_ERROR", "GitHub indisponível agora.");
			}
			return objectMapper.readTree(response.body());
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new ApiException(502, "IMPORT_SOURCE_ERROR", "Consulta ao GitHub interrompida.");
		}
		catch (ApiException apiException) {
			throw apiException;
		}
		catch (Exception failure) {
			throw new ApiException(502, "IMPORT_SOURCE_ERROR", "Falha ao consultar o GitHub.");
		}
	}

	/** Uma issue vira vaga. Sem título ou sem URL não dá para listar. */
	ExternalJob toJob(JsonNode issue) {
		String title = text(issue, "title");
		String url = text(issue, "html_url");
		if (!StringUtils.hasText(title) || !StringUtils.hasText(url)) {
			return null;
		}
		String body = text(issue, "body");
		var sections = sections(body);
		List<String> labels = labels(issue);

		// O título traz o modelo entre colchetes: "[Remoto] Desenvolvedor(a) Backend Pleno - Empresa".
		String cleanTitle = title.replaceFirst("^\\s*\\[[^\\]]*\\]\\s*", "").trim();
		String bracket = title.startsWith("[") ? title.substring(1, Math.max(1, title.indexOf(']'))) : null;

		String level = firstNonBlank(sections.get("nível"), sections.get("nivel"), labelMatching(labels, "júnior",
			"junior", "pleno", "sênior", "senior", "estágio", "estagio", "trainee"));
		String employment = firstNonBlank(sections.get("regime de contratação"), sections.get("regime de contratacao"),
			sections.get("tipo de contratação"), labelMatching(labels, "clt", "pj", "estágio", "estagio",
				"freelance", "contrato"));
		String workModel = firstNonBlank(sections.get("modelo de trabalho"), sections.get("modelo"),
			bracket, labelMatching(labels, "remoto", "remote", "híbrido", "hibrido", "presencial"));
		String location = firstNonBlank(sections.get("localização"), sections.get("localizacao"),
			sections.get("local"), labelMatching(labels, "brasil", "são paulo", "sao paulo", "rio de janeiro"));
		String company = firstNonBlank(sections.get("empresa"), sections.get("sobre a empresa"));
		String apply = sections.get("como se candidatar");

		Instant publishedAt = instant(issue, "created_at");
		return new ExternalJob(String.valueOf(issue.path("number").asLong()), cleanTitle, company, location,
			body, null, employment, url, url, name(), publishedAt,
			new Meta(com.socialcoisa.rede.jobs.domain.JobTaxonomy.level(level),
				com.socialcoisa.rede.jobs.domain.JobTaxonomy.workModel(workModel),
				null, null, null, null, null, null, null, url, null));
	}

	/**
	 * Quebra o corpo em seções por cabeçalho Markdown. A chave é o cabeçalho em minúsculas, sem
	 * pontuação final; o valor é o texto até o próximo cabeçalho.
	 */
	static java.util.Map<String, String> sections(String body) {
		java.util.Map<String, String> sections = new java.util.LinkedHashMap<>();
		if (!StringUtils.hasText(body)) {
			return sections;
		}
		Matcher matcher = SECTION.matcher(body);
		List<int[]> bounds = new ArrayList<>();
		List<String> names = new ArrayList<>();
		while (matcher.find()) {
			bounds.add(new int[] { matcher.end(), matcher.start() });
			names.add(matcher.group(1).toLowerCase(Locale.ROOT).replaceAll("[*_`]", "").trim());
		}
		for (int i = 0; i < bounds.size(); i++) {
			int start = bounds.get(i)[0];
			int end = i + 1 < bounds.size() ? bounds.get(i + 1)[1] : body.length();
			String value = body.substring(start, Math.min(end, body.length())).trim();
			if (StringUtils.hasText(value)) {
				sections.putIfAbsent(names.get(i), value);
			}
		}
		return sections;
	}

	private static List<String> labels(JsonNode issue) {
		List<String> labels = new ArrayList<>();
		JsonNode array = issue.path("labels");
		if (array.isArray()) {
			for (JsonNode label : array) {
				String name = label.path("name").asString("");
				if (StringUtils.hasText(name)) {
					labels.add(name.trim());
				}
			}
		}
		return labels;
	}

	/** A primeira label que contém um dos termos (a label é o dado estruturado da comunidade). */
	private static String labelMatching(List<String> labels, String... terms) {
		for (String label : labels) {
			String lower = label.toLowerCase(Locale.ROOT);
			for (String term : terms) {
				if (lower.contains(term)) {
					return label;
				}
			}
		}
		return null;
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.asString();
		return StringUtils.hasText(text) ? text.trim() : null;
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
}
