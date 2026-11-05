package com.socialcoisa.rede.mentoring.adapters.out.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.mentoring.application.port.out.MentoringTokenGateway;
import com.socialcoisa.rede.shared.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Token de entrada numa sala de mentoria no Epapo.
 *
 * <p>Mesmo caminho das chamadas: o gateway do Epapo recebe só o id da sessão e o id de quem entra,
 * pergunta a esta aplicação se aquela pessoa participa daquela sessão
 * ({@code POST /mentoring/{sessionId}/gateway/entry/{userId}}, com segredo compartilhado) e só
 * então assina. Sala, nome e moderação vêm da resposta dessa rota, nunca do pedido.
 *
 * <p>Sem gateway configurado a resposta é 503 e nada abre — nunca uma sala pública aberta.
 */
@Component
class EpapoMentoringTokenGateway implements MentoringTokenGateway {

	private static final Logger LOG = LoggerFactory.getLogger(EpapoMentoringTokenGateway.class);

	private final ObjectMapper json;
	private final HttpClient http;
	private final String gatewayUrl;
	private final String internalToken;
	private final Duration timeout;
	private final Duration ttl;

	EpapoMentoringTokenGateway(ObjectMapper json,
			@Value("${rede.calls.epapo.gateway-url:}") String gatewayUrl,
			@Value("${rede.calls.epapo.internal-token:}") String internalToken,
			@Value("${rede.calls.epapo.timeout-millis:5000}") long timeoutMillis,
			@Value("${rede.calls.epapo.token-ttl-minutes:120}") long ttlMinutes) {
		this.json = json;
		this.gatewayUrl = normalize(gatewayUrl);
		this.internalToken = internalToken == null ? "" : internalToken.trim();
		this.timeout = Duration.ofMillis(Math.max(500, Math.min(timeoutMillis, 30_000)));
		this.ttl = Duration.ofMinutes(Math.max(1, ttlMinutes));
		this.http = HttpClient.newBuilder()
			.connectTimeout(this.timeout)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	@Override
	public long tokenTtlSeconds() {
		return ttl.toSeconds();
	}

	@Override
	public String tokenFor(String sessionId, String userId) {
		if (gatewayUrl == null || internalToken.isEmpty()) {
			throw new ApiException(503, "MENTORING_ROOMS_NOT_CONFIGURED",
				"A sala de mentoria ainda nao esta configurada neste ambiente.");
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("sessionId", sessionId);
		payload.put("userId", userId);
		HttpRequest request = HttpRequest.newBuilder(URI.create(gatewayUrl + "/auth/token"))
			.timeout(timeout)
			.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8))
			.header("Content-Type", "application/json")
			.header("X-Epapo-Internal", internalToken)
			.build();
		String token = read(send(request).body()).path("jwt").asText("");
		if (token.isBlank()) {
			LOG.error("gateway do Epapo respondeu sem token de mentoria");
			throw unavailable();
		}
		return token;
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(
				StandardCharsets.UTF_8));
			if (response.statusCode() / 100 == 2) {
				return response;
			}
			// Só o motivo vai para o log: o corpo de erro do gateway não carrega token nem segredo.
			LOG.warn("gateway do Epapo recusou token de mentoria: status={} motivo={}",
				response.statusCode(), reason(response.body()));
			int status = response.statusCode();
			if (status == 403 || status == 404 || status == 409) {
				throw new ApiException(409, "MENTORING_ENTRY_DENIED",
					"Voce nao pode entrar nesta sessao agora.");
			}
			throw unavailable();
		}
		catch (java.io.IOException failure) {
			LOG.warn("gateway do Epapo indisponivel para mentoria: {}", failure.getClass().getSimpleName());
			throw unavailable();
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw unavailable();
		}
		catch (IllegalArgumentException badUrl) {
			LOG.error("URL do gateway do Epapo invalida: {}", badUrl.getClass().getSimpleName());
			throw unavailable();
		}
	}

	private JsonNode read(String body) {
		try {
			return json.readTree(body == null || body.isBlank() ? "{}" : body);
		}
		catch (RuntimeException broken) {
			LOG.error("gateway do Epapo respondeu algo que nao e JSON");
			throw unavailable();
		}
	}

	/** Só o código de motivo do gateway vai para o log, nunca o corpo inteiro nem um token. */
	private static String reason(String body) {
		if (body == null || body.isBlank()) {
			return "vazio";
		}
		int key = body.indexOf("\"error\"");
		int colon = key < 0 ? -1 : body.indexOf(':', key);
		int open = colon < 0 ? -1 : body.indexOf('"', colon);
		int close = open < 0 ? -1 : body.indexOf('"', open + 1);
		if (close < 0) {
			return "sem-codigo";
		}
		return body.substring(open + 1, Math.min(close, open + 81)).replaceAll("[^A-Za-z0-9_.:-]", "_");
	}

	private static String normalize(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String trimmed = raw.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static ApiException unavailable() {
		return new ApiException(503, "MENTORING_ROOMS_UNAVAILABLE",
			"A sala de mentoria esta temporariamente indisponivel. Tente de novo em instantes.");
	}
}
