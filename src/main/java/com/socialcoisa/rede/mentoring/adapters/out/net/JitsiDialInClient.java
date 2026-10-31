package com.socialcoisa.rede.mentoring.adapters.out.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.mentoring.application.port.out.JitsiDialInGateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A API pública do Jitsi (api.jitsi.net): a criação de sala não tem chamada — qualquer nome vira
 * sala quando alguém entra. O que a API oferece de verdade é o acesso por telefone (dial-in) e o
 * PIN da conferência, que é o que esta classe busca.
 *
 * <p>Falha de rede/API nunca propaga: a sala continua funcionando no navegador e o aviso de
 * discagem é só um extra. Por isso tudo devolve Optional/lista vazia em vez de lançar.
 */
@Component
class JitsiDialInClient implements JitsiDialInGateway {

	private final ObjectMapper objectMapper;
	private final HttpClient http;
	private final String apiBase;

	JitsiDialInClient(ObjectMapper objectMapper,
			@Value("${rede.mentoring.jitsi.api-base:https://api.jitsi.net}") String apiBase) {
		this.objectMapper = objectMapper;
		String trimmed = apiBase == null ? "" : apiBase.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		this.apiBase = trimmed;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	}

	@Override
	public List<String> phoneNumbers(String conferenceName) {
		Optional<JsonNode> body = get("/phoneNumberList?conference=" + encode(conferenceName));
		if (body.isEmpty()) {
			return List.of();
		}
		JsonNode numbers = body.get().path("numbers");
		if (!numbers.isObject()) {
			return List.of();
		}
		// O Brasil primeiro quando existe: é quem vai usar o dial-in na prática.
		List<String> ordered = new ArrayList<>();
		appendNumbers(ordered, numbers.path("Brazil"));
		List<String> others = new ArrayList<>();
		for (java.util.Map.Entry<String, JsonNode> entry : numbers.properties()) {
			if ("Brazil".equals(entry.getKey())) {
				continue;
			}
			appendNumbers(others, entry.getValue());
		}
		ordered.addAll(others);
		return List.copyOf(ordered);
	}

	@Override
	public Optional<String> conferencePin(String conferenceName) {
		Optional<JsonNode> body = get("/conferenceMapper?conference=" + encode(conferenceName));
		if (body.isEmpty()) {
			return Optional.empty();
		}
		long id = body.get().path("id").asLong(0L);
		return id <= 0 ? Optional.empty() : Optional.of(Long.toString(id));
	}

	private void appendNumbers(List<String> target, JsonNode entries) {
		if (entries == null || !entries.isArray()) {
			return;
		}
		for (JsonNode entry : entries) {
			String number = entry.asString("");
			if (number != null && !number.isBlank()) {
				target.add(number);
			}
		}
	}

	private Optional<JsonNode> get(String path) {
		if (apiBase.isBlank()) {
			return Optional.empty();
		}
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + path))
				.timeout(Duration.ofSeconds(4))
				.header("Accept", "application/json")
				.GET()
				.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				return Optional.empty();
			}
			return Optional.of(objectMapper.readTree(response.body()));
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (Exception unavailable) {
			return Optional.empty();
		}
	}

	private String encode(String value) {
		return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
	}

}
