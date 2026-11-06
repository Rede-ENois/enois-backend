package com.socialcoisa.rede.suggestions.adapters.out.github;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.suggestions.application.port.out.SuggestionIssueGateway;
import com.socialcoisa.rede.suggestions.domain.SuggestionTarget;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Opens the public issues of approved suggestions. Token and repositories are this module's own
 * configuration ({@code rede.suggestions.github.*}): the token only needs Issues write on the public
 * target repositories, so it never reaches the private support repository. Each environment points to
 * its own repositories (QA to a sandbox). A missing token or repository turns the target off.
 *
 * Every issue carries a hidden key (the suggestion id). After an attempt whose outcome is unknown (a
 * timeout after GitHub created the issue), the next approval finds that issue by its key instead of
 * opening a duplicate. Network/API failures never propagate and response bodies never reach the log.
 */
@Component
class SuggestionIssueClient implements SuggestionIssueGateway {

	private static final Logger LOG = LoggerFactory.getLogger(SuggestionIssueClient.class);
	private static final String REPOSITORY_FORMAT = "[\\w.-]+/[\\w.-]+";
	/** Margin for clock skew between this server and GitHub when looking up a previous attempt. */
	private static final Duration LOOKUP_MARGIN = Duration.ofMinutes(10);
	private static final int LOOKUP_PAGE_SIZE = 100;
	/** Past this many full pages the outcome stays unknown: the caller must not create blindly. */
	private static final int LOOKUP_MAX_PAGES = 10;

	private final ObjectMapper objectMapper;
	private final HttpClient http;
	private final String apiBase;
	private final String token;
	private final Map<SuggestionTarget, String> repositories = new EnumMap<>(SuggestionTarget.class);

	SuggestionIssueClient(ObjectMapper objectMapper,
			@Value("${rede.suggestions.github.api-base:https://api.github.com}") String apiBase,
			@Value("${rede.suggestions.github.token:}") String token,
			@Value("${rede.suggestions.github.repo.backend:}") String backendRepo,
			@Value("${rede.suggestions.github.repo.frontend:}") String frontendRepo,
			@Value("${rede.suggestions.github.repo.design-system:}") String designSystemRepo) {
		this.objectMapper = objectMapper;
		this.apiBase = apiBase == null ? "" : apiBase.trim();
		this.token = token == null ? "" : token.trim();
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
		register(SuggestionTarget.BACKEND, backendRepo);
		register(SuggestionTarget.FRONTEND, frontendRepo);
		register(SuggestionTarget.DESIGN_SYSTEM, designSystemRepo);
	}

	private void register(SuggestionTarget target, String repository) {
		String clean = repository == null ? "" : repository.trim();
		if (clean.matches(REPOSITORY_FORMAT)) {
			repositories.put(target, clean);
		}
		else if (!clean.isEmpty()) {
			LOG.warn("Repositório de sugestões de {} fora do formato dono/nome; destino desligado.", target);
		}
	}

	@Override
	public Availability availability(SuggestionTarget target) {
		if (token.isEmpty()) {
			return Availability.TOKEN_MISSING;
		}
		return repositories.containsKey(target) ? Availability.READY : Availability.REPOSITORY_MISSING;
	}

	@Override
	public Optional<IssueRef> publish(SuggestionTarget target, String key, String title, String body,
			Instant previousAttempt) {
		if (availability(target) != Availability.READY) {
			return Optional.empty();
		}
		String repository = repositories.get(target);
		String marker = marker(key);
		try {
			if (previousAttempt != null) {
				Lookup lookup = findByMarker(repository, marker, previousAttempt.minus(LOOKUP_MARGIN));
				if (lookup == null) {
					return Optional.empty();
				}
				if (lookup.issue() != null) {
					return Optional.of(lookup.issue());
				}
			}
			return create(target, repository, title, body + "\n\n" + marker);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (Exception failure) {
			LOG.warn("Falha ao publicar issue de sugestão ({}): {}", target, failure.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	/** Hidden in the rendered issue; an opaque id, never anything about the author. */
	static String marker(String key) {
		return "<!-- enois-suggestion:" + key + " -->";
	}

	private Optional<IssueRef> create(SuggestionTarget target, String repository, String title, String body)
			throws Exception {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("title", title);
		payload.put("body", body);
		HttpResponse<String> response = http.send(request("/repos/" + repository + "/issues")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
			.build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			LOG.warn("GitHub respondeu {} ao abrir issue de sugestão ({})", response.statusCode(), target);
			return Optional.empty();
		}
		return Optional.ofNullable(issueRef(objectMapper.readTree(response.body())));
	}

	/**
	 * Pages through the issues touched since the previous attempt (newest first) and returns the one
	 * carrying the marker. The REST list is read-your-writes, unlike the search API. Null when the
	 * outcome stays unknown (GitHub did not answer, or more issues than the page budget): the caller
	 * must not create blindly then.
	 */
	private Lookup findByMarker(String repository, String marker, Instant since) throws Exception {
		String query = "?state=all&sort=created&direction=desc&per_page=" + LOOKUP_PAGE_SIZE + "&since="
				+ URLEncoder.encode(since.truncatedTo(ChronoUnit.SECONDS).toString(), StandardCharsets.UTF_8);
		for (int page = 1; page <= LOOKUP_MAX_PAGES; page++) {
			HttpResponse<String> response = http.send(
				request("/repos/" + repository + "/issues" + query + "&page=" + page).GET().build(),
				HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				LOG.warn("GitHub respondeu {} ao procurar issue de sugestão já aberta", response.statusCode());
				return null;
			}
			JsonNode issues = objectMapper.readTree(response.body());
			for (JsonNode issue : issues) {
				if (issue.path("body").asString("").contains(marker)) {
					return new Lookup(issueRef(issue));
				}
			}
			if (issues.size() < LOOKUP_PAGE_SIZE) {
				return new Lookup(null);
			}
		}
		LOG.warn("Issue de sugestão não encontrada em {} páginas; publicação adiada", LOOKUP_MAX_PAGES);
		return null;
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create(apiBase + path))
			.timeout(Duration.ofSeconds(6))
			.header("Accept", "application/vnd.github+json")
			.header("Authorization", "Bearer " + token)
			.header("X-GitHub-Api-Version", "2022-11-28")
			.header("User-Agent", "enois-sugestoes/1.0");
	}

	private static IssueRef issueRef(JsonNode issue) {
		int number = issue.path("number").asInt(0);
		if (number <= 0) {
			return null;
		}
		String url = issue.path("html_url").asString("");
		return new IssueRef(number, url == null || url.isBlank() ? null : url);
	}

	private record Lookup(IssueRef issue) {
	}
}
