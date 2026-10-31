package com.socialcoisa.rede.suggestions.adapters.out.github;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 * Abre as issues públicas das sugestões. Um token e um repositório por destino, só por variável de
 * ambiente; repositório ausente ou fora do formato dono/nome deixa aquele destino desligado.
 *
 * O token é próprio das sugestões e nunca o do suporte: aquele repositório é privado e tem dado
 * pessoal. Falha de rede/API nunca propaga e o corpo da resposta nunca vai para o log.
 */
@Component
class SuggestionIssueClient implements SuggestionIssueGateway {

	private static final Logger LOG = LoggerFactory.getLogger(SuggestionIssueClient.class);
	private static final String REPOSITORY_FORMAT = "[\\w.-]+/[\\w.-]+";

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
	public boolean enabled(SuggestionTarget target) {
		return !token.isEmpty() && repositories.containsKey(target);
	}

	@Override
	public Optional<IssueRef> createIssue(SuggestionTarget target, String title, String body) {
		if (!enabled(target)) {
			return Optional.empty();
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("title", title);
		payload.put("body", body);
		String path = "/repos/" + repositories.get(target) + "/issues";
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + path))
				.timeout(Duration.ofSeconds(6))
				.header("Accept", "application/vnd.github+json")
				.header("Authorization", "Bearer " + token)
				.header("X-GitHub-Api-Version", "2022-11-28")
				.header("User-Agent", "enois-sugestoes/1.0")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
				.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				LOG.warn("GitHub respondeu {} ao abrir issue de sugestão ({})", response.statusCode(), target);
				return Optional.empty();
			}
			JsonNode issue = objectMapper.readTree(response.body());
			int number = issue.path("number").asInt(0);
			String url = issue.path("html_url").asString("");
			if (number <= 0) {
				return Optional.empty();
			}
			return Optional.of(new IssueRef(number, url == null || url.isBlank() ? null : url));
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (Exception failure) {
			LOG.warn("Falha ao abrir issue de sugestão ({}): {}", target, failure.getClass().getSimpleName());
			return Optional.empty();
		}
	}
}
