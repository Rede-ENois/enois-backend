package com.socialcoisa.rede.contributions.adapters.out.github;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.contributions.application.port.out.GitHubGateway;
import com.socialcoisa.rede.contributions.application.port.out.GitHubIssue;
import com.socialcoisa.rede.contributions.application.port.out.GitHubPullRequest;
import com.socialcoisa.rede.githubprofile.application.port.out.GitHubProfileGateway;

/**
 * Cliente REST do GitHub para evidências de contribuição.
 *
 * <p>Só leitura pública dos repositórios da organização. O token é opcional (a API pública tem
 * limite baixo); quando existe, usa um token fine-grained de leitura. O cliente nunca recebe
 * conteúdo de usuário: apenas PRs integrados, arquivos e revisões.</p>
 */
@Component
public class GitHubApiClient implements GitHubGateway, GitHubProfileGateway {

	private static final int MAX_FILES = 500;
	private static final int FILE_PAGES = 3;

	private final HttpClient http;
	private final ObjectMapper json = new ObjectMapper();
	private final String apiBase;
	private final String token;
	private final String userAgent;

	public GitHubApiClient(
			@Value("${rede.github.api-base:https://api.github.com}") String apiBase,
			@Value("${rede.github.token:}") String token,
			@Value("${rede.github.user-agent:EnoisBot/1.0 (+https://enois.ia.br)}") String userAgent) {
		this.apiBase = apiBase.replaceAll("/+$", "");
		this.token = token == null ? "" : token.trim();
		this.userAgent = userAgent;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	}

	@Override
	public List<GitHubPullRequest> listMergedPullRequests(String repository, Instant since, int maxPages) {
		List<GitHubPullRequest> result = new ArrayList<>();
		int pages = Math.max(1, Math.min(maxPages, 20));
		for (int page = 1; page <= pages; page++) {
			JsonNode array = get("/repos/" + repository + "/pulls?state=closed&sort=updated&direction=desc"
				+ "&per_page=100&page=" + page);
			if (!array.isArray() || array.isEmpty()) {
				break;
			}
			boolean reachedSince = false;
			for (JsonNode pr : array) {
				Instant updatedAt = instant(pr, "updated_at");
				if (since != null && updatedAt != null && updatedAt.isBefore(since)) {
					reachedSince = true;
					continue;
				}
				if (pr.path("merged_at").isNull() || pr.path("merged_at").isMissingNode()) {
					continue;
				}
				result.add(fromPull(pr));
			}
			if (reachedSince || array.size() < 100) {
				break;
			}
		}
		return result;
	}

	@Override
	public List<String> listOrganizationRepositories(String organization, int maxPages) {
		List<String> result = new ArrayList<>();
		int pages = Math.max(1, Math.min(maxPages, 10));
		for (int page = 1; page <= pages; page++) {
			JsonNode array = get("/orgs/" + URLEncoder.encode(organization, StandardCharsets.UTF_8)
				+ "/repos?type=public&per_page=100&page=" + page);
			if (!array.isArray() || array.isEmpty()) {
				break;
			}
			for (JsonNode repo : array) {
				String fullName = repo.path("full_name").asText("");
				if (fullName.isBlank() || repo.path("private").asBoolean(false) || repo.path("fork").asBoolean(false)
						|| repo.path("archived").asBoolean(false) || repo.path("disabled").asBoolean(false)) {
					continue;
				}
				result.add(fullName);
			}
			if (array.size() < 100) {
				break;
			}
		}
		return result;
	}

	@Override
	public List<GitHubIssue> listIssues(String repository, Instant since, int maxPages) {
		List<GitHubIssue> result = new ArrayList<>();
		int pages = Math.max(1, Math.min(maxPages, 20));
		String sinceParam = since == null ? ""
			: "&since=" + URLEncoder.encode(since.toString(), StandardCharsets.UTF_8);
		for (int page = 1; page <= pages; page++) {
			JsonNode array = get("/repos/" + repository + "/issues?state=all&sort=updated&direction=desc"
				+ "&per_page=100&page=" + page + sinceParam);
			if (!array.isArray() || array.isEmpty()) {
				break;
			}
			boolean reachedSince = false;
			for (JsonNode issue : array) {
				// A API mistura PRs no endpoint de issues; eles contam pelo endpoint de pulls.
				if (!issue.path("pull_request").isMissingNode()) {
					continue;
				}
				Instant updatedAt = instant(issue, "updated_at");
				if (since != null && updatedAt != null && updatedAt.isBefore(since)) {
					reachedSince = true;
					continue;
				}
				result.add(fromIssue(issue));
			}
			if (reachedSince || array.size() < 100) {
				break;
			}
		}
		return result;
	}

	@Override
	public List<GitHubPullRequest> listOpenPullRequests(String repository, int maxPages) {
		List<GitHubPullRequest> result = new ArrayList<>();
		int pages = Math.max(1, Math.min(maxPages, 10));
		for (int page = 1; page <= pages; page++) {
			JsonNode array = get("/repos/" + repository + "/pulls?state=open&sort=created&direction=desc"
				+ "&per_page=100&page=" + page);
			if (!array.isArray() || array.isEmpty()) {
				break;
			}
			for (JsonNode pr : array) {
				result.add(fromPull(pr));
			}
			if (array.size() < 100) {
				break;
			}
		}
		return result;
	}

	@Override
	public boolean submitReview(String repository, long number, String decision, String body,
			String tokenOverride) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("event", decision);
		if (body != null && !body.isBlank()) {
			payload.put("body", body);
		}
		try {
			String jsonBody = json.writeValueAsString(payload);
			HttpResponse<String> response = send(apiBase + "/repos/" + repository + "/pulls/" + number + "/reviews",
				"application/vnd.github+json", "POST", jsonBody, tokenOverride);
			return response.statusCode() / 100 == 2;
		}
		catch (Exception failure) {
			return false;
		}
	}

	@Override
	public GitHubPullRequest withDetails(String repository, GitHubPullRequest pr) {
		List<String> files = new ArrayList<>();
		for (int page = 1; page <= FILE_PAGES && files.size() < MAX_FILES; page++) {
			JsonNode array = get("/repos/" + repository + "/pulls/" + pr.number()
				+ "/files?per_page=100&page=" + page);
			if (!array.isArray() || array.isEmpty()) {
				break;
			}
			for (JsonNode file : array) {
				if (files.size() >= MAX_FILES) {
					break;
				}
				String filename = file.path("filename").asText("");
				if (!filename.isBlank()) {
					files.add(filename);
				}
			}
			if (array.size() < 100) {
				break;
			}
		}

		int reviews = 0;
		int approvals = 0;
		int changesRequested = 0;
		for (int page = 1; page <= 2; page++) {
			JsonNode array = get("/repos/" + repository + "/pulls/" + pr.number()
				+ "/reviews?per_page=100&page=" + page);
			if (!array.isArray() || array.isEmpty()) {
				break;
			}
			for (JsonNode review : array) {
				reviews++;
				String state = review.path("state").asText("");
				if ("APPROVED".equals(state)) {
					approvals++;
				}
				else if ("CHANGES_REQUESTED".equals(state)) {
					changesRequested++;
				}
			}
			if (array.size() < 100) {
				break;
			}
		}

		return new GitHubPullRequest(pr.number(), pr.nodeId(), pr.title(), pr.url(), pr.body(), pr.authorId(),
			pr.authorLogin(), pr.state(), pr.openedAt(), pr.mergedAt(), pr.updatedAt(), pr.additions(),
			pr.deletions(), pr.changedFiles(), List.copyOf(files), reviews, approvals, changesRequested);
	}

	private GitHubPullRequest fromPull(JsonNode pr) {
		JsonNode user = pr.path("user");
		return new GitHubPullRequest(pr.path("number").asLong(), pr.path("node_id").asText(""),
			pr.path("title").asText(""), pr.path("html_url").asText(""), pr.path("body").asText(""),
			user.path("id").asText(""), user.path("login").asText(""), pr.path("state").asText("closed"),
			instant(pr, "created_at"), instant(pr, "merged_at"), instant(pr, "updated_at"),
			pr.path("additions").asInt(0), pr.path("deletions").asInt(0), pr.path("changed_files").asInt(0),
			List.of(), 0, 0, 0);
	}

	private GitHubIssue fromIssue(JsonNode issue) {
		JsonNode user = issue.path("user");
		List<String> labels = new ArrayList<>();
		JsonNode labelNodes = issue.path("labels");
		if (labelNodes.isArray()) {
			for (JsonNode label : labelNodes) {
				String name = label.path("name").asText("");
				if (!name.isBlank()) {
					labels.add(name);
				}
			}
		}
		return new GitHubIssue(issue.path("number").asLong(), issue.path("node_id").asText(""),
			issue.path("title").asText(""), issue.path("html_url").asText(""), user.path("id").asText(""),
			user.path("login").asText(""), issue.path("state").asText("open"), instant(issue, "created_at"),
			instant(issue, "closed_at"), instant(issue, "updated_at"), List.copyOf(labels));
	}

	@Override
	public List<GitHubUserRepository> listUserRepositories(String login, int maxPages) {
		List<GitHubUserRepository> result = new ArrayList<>();
		String safeLogin = encode(login == null ? "" : login);
		int pages = Math.max(1, Math.min(maxPages, 20));
		for (int page = 1; page <= pages; page++) {
			JsonNode array = get("/users/" + safeLogin + "/repos?per_page=100&page=" + page + "&sort=updated");
			if (!array.isArray() || array.isEmpty()) {
				break;
			}
			for (JsonNode repo : array) {
				if (repo.path("private").asBoolean(false)) {
					continue;
				}
				List<String> topics = new ArrayList<>();
				for (JsonNode topic : repo.path("topics")) {
					String value = topic.asText("");
					if (!value.isBlank()) {
						topics.add(value);
					}
				}
				result.add(new GitHubUserRepository(repo.path("full_name").asText(""), repo.path("name").asText(""),
					textOrNull(repo, "description"), repo.path("html_url").asText(""), textOrNull(repo, "homepage"),
					textOrNull(repo, "language"), List.copyOf(topics), repo.path("stargazers_count").asInt(0),
					repo.path("forks_count").asInt(0), repo.path("fork").asBoolean(false),
					repo.path("archived").asBoolean(false), instant(repo, "updated_at"),
					repo.path("default_branch").asText("main")));
			}
			if (array.size() < 100) {
				break;
			}
		}
		return result;
	}

	@Override
	public Optional<GitHubProfileReadme> profileReadme(String login) {
		String safeLogin = encode(login == null ? "" : login);
		JsonNode repo = getOptional("/repos/" + safeLogin + "/" + safeLogin);
		if (repo == null) {
			return Optional.empty();
		}
		JsonNode readme = getOptional("/repos/" + safeLogin + "/" + safeLogin + "/readme");
		if (readme == null) {
			return Optional.empty();
		}
		String download = readme.path("download_url").asText("");
		if (!download.startsWith("https://raw.githubusercontent.com/")) {
			return Optional.empty();
		}
		String markdown = getRaw(download);
		if (markdown == null || markdown.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(new GitHubProfileReadme(markdown, repo.path("html_url").asText(""),
			repo.path("default_branch").asText("main")));
	}

	private JsonNode getOptional(String path) {
		HttpResponse<String> response = send(apiBase + path, "application/vnd.github+json");
		if (response.statusCode() == 404) {
			return null;
		}
		return readJson(response, path);
	}

	private JsonNode get(String path) {
		return readJson(send(apiBase + path, "application/vnd.github+json"), path);
	}

	private JsonNode readJson(HttpResponse<String> response, String label) {
		if (response.statusCode() == 403 || response.statusCode() == 429) {
			String remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse("?");
			throw new GitHubRateLimitException("GitHub recusou a chamada (status=" + response.statusCode()
				+ ", remaining=" + remaining + ")");
		}
		if (response.statusCode() >= 400) {
			throw new IllegalStateException("GitHub respondeu " + response.statusCode() + " em " + label);
		}
		try {
			return json.readTree(response.body());
		}
		catch (Exception failure) {
			throw new IllegalStateException("Falha ao ler a resposta do GitHub: " + failure.getMessage(), failure);
		}
	}

	private String getRaw(String absoluteUrl) {
		HttpResponse<String> response = send(absoluteUrl, "text/plain");
		if (response.statusCode() == 404) {
			return null;
		}
		if (response.statusCode() == 403 || response.statusCode() == 429) {
			throw new GitHubRateLimitException("GitHub recusou a chamada (status=" + response.statusCode() + ")");
		}
		if (response.statusCode() >= 400) {
			throw new IllegalStateException("GitHub respondeu " + response.statusCode() + " no README");
		}
		return response.body();
	}

	private HttpResponse<String> send(String url, String accept) {
		return send(url, accept, "GET", null, null);
	}

	private HttpResponse<String> send(String url, String accept, String method, String body,
			String tokenOverride) {
		try {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(15))
				.header("Accept", accept)
				.header("X-GitHub-Api-Version", "2022-11-28")
				.header("User-Agent", userAgent);
			// O token so vai para a API do GitHub. O README cru baixa de raw.githubusercontent.com,
			// que e publico e nao precisa (nem deve) receber a credencial.
			String bearer = StringUtils.hasText(tokenOverride) ? tokenOverride : token;
			if (StringUtils.hasText(bearer) && url.startsWith(apiBase)) {
				builder.header("Authorization", "Bearer " + bearer);
			}
			HttpRequest.BodyPublisher publisher = body == null
				? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
			if (body != null) {
				builder.header("Content-Type", "application/json");
			}
			return http.send(builder.method(method, publisher).build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (Exception failure) {
			throw new IllegalStateException("Falha ao consultar o GitHub: " + failure.getMessage(), failure);
		}
	}

	private static String textOrNull(JsonNode node, String field) {
		String value = node.path(field).asText("");
		return value.isBlank() ? null : value;
	}

	private static Instant instant(JsonNode node, String field) {
		String value = node.path(field).asText("");
		return value.isBlank() ? null : Instant.parse(value);
	}

	/** Codifica o nome do repositório para a URL (owner/name não precisa, mas mantém a regra). */
	static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	Optional<String> tokenConfigured() {
		return StringUtils.hasText(token) ? Optional.of("configured") : Optional.empty();
	}
}
