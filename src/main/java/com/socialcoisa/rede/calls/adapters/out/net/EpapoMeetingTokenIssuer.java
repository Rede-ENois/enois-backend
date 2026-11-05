package com.socialcoisa.rede.calls.adapters.out.net;

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

import com.socialcoisa.rede.calls.application.port.out.MeetingTokenIssuer;
import com.socialcoisa.rede.calls.domain.CallParticipant;
import com.socialcoisa.rede.calls.domain.CallSession;
import com.socialcoisa.rede.shared.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Token de entrada no Epapo, o servidor de reunião que a gente hospeda.
 *
 * <p>A assinatura do token não acontece aqui. O gateway do Epapo (na rede privada) recebe desta
 * aplicação só o id da chamada e o id de quem entra; ele pergunta a este backend se aquela pessoa
 * pode mesmo entrar naquela chamada — {@code POST /calls/{callId}/gateway/entry/{userId}}, com um
 * segredo compartilhado — e só então assina. Sala, nome e moderação vêm da resposta dessa rota,
 * nunca do pedido: o token emitido já nasce preso à sala certa.
 *
 * <p>O segredo de assinatura do Jitsi fica apenas nos containers do servidor de reunião. Esta
 * aplicação nunca o vê, e o navegador só recebe o token pronto.
 *
 * <p>Sem gateway configurado o serviço responde 503 e nada abre — nunca uma instância pública
 * aberta. Falha do gateway também é 503 explícito: o painel mostra erro em vez de fingir chamada.
 */
@Component
class EpapoMeetingTokenIssuer implements MeetingTokenIssuer {

	private static final Logger LOG = LoggerFactory.getLogger(EpapoMeetingTokenIssuer.class);

	private final ObjectMapper json;
	private final HttpClient http;
	private final String host;
	private final String gatewayUrl;
	private final String internalToken;
	private final Duration timeout;
	private final Duration ttl;

	EpapoMeetingTokenIssuer(ObjectMapper json,
			@Value("${rede.calls.jitsi.url:}") String host,
			@Value("${rede.calls.epapo.gateway-url:}") String gatewayUrl,
			@Value("${rede.calls.epapo.internal-token:}") String internalToken,
			@Value("${rede.calls.epapo.timeout-millis:5000}") long timeoutMillis,
			@Value("${rede.calls.epapo.token-ttl-minutes:120}") long ttlMinutes) {
		this.json = json;
		this.host = normalize(host);
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
	public boolean isConfigured() {
		return host != null && gatewayUrl != null && !internalToken.isEmpty();
	}

	@Override
	public String host() {
		if (!isConfigured()) {
			throw notConfigured();
		}
		return host;
	}

	@Override
	public long tokenTtlSeconds() {
		return ttl.toSeconds();
	}

	@Override
	public String tokenFor(CallSession call, CallParticipant participant, String displayName, boolean moderator) {
		if (!isConfigured()) {
			throw notConfigured();
		}
		// Só os identificadores: o gateway confere a autorização sozinho, chamando a rota interna
		// deste backend, e monta o resto do token a partir da resposta — nada aqui é confiado de
		// graça, nem o nome de quem entra.
		return requestToken(call.id(), participant.userId());
	}

	private String requestToken(String callId, String userId) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("callId", callId);
		payload.put("userId", userId);
		HttpRequest request = HttpRequest.newBuilder(uri("/auth/token"))
			.timeout(timeout)
			.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload),
				StandardCharsets.UTF_8))
			.header("Content-Type", "application/json")
			.header("X-Epapo-Internal", internalToken)
			.build();
		String token = read(send(request, "emissao do token").body()).path("jwt").asText("");
		if (token.isBlank()) {
			LOG.error("gateway do Epapo respondeu sem token");
			throw unavailable();
		}
		return token;
	}

	private HttpResponse<String> send(HttpRequest request, String what) {
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(
				StandardCharsets.UTF_8));
			if (response.statusCode() / 100 == 2) {
				return response;
			}
			// O corpo de erro do gateway diz se a recusa foi de sessão ou de vínculo; ele não carrega
			// token nem segredo. Só o motivo vai para o log.
			LOG.warn("gateway do Epapo recusou {}: status={} motivo={}", what, response.statusCode(),
				reason(response.body()));
			int status = response.statusCode();
			if (status == 403 || status == 404 || status == 409) {
				throw new ApiException(409, "CALL_ENTRY_DENIED", "Voce nao pode entrar nesta chamada agora.");
			}
			throw unavailable();
		}
		catch (java.io.IOException failure) {
			LOG.warn("gateway do Epapo indisponivel em {}: {}", what, failure.getClass().getSimpleName());
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

	private URI uri(String path) {
		return URI.create(gatewayUrl + path);
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

	private static ApiException notConfigured() {
		return new ApiException(503, "CALLS_NOT_CONFIGURED",
			"As chamadas ainda nao estao configuradas neste ambiente.");
	}

	private static ApiException unavailable() {
		return new ApiException(503, "CALLS_UNAVAILABLE",
			"As chamadas estao temporariamente indisponiveis. Tente de novo em instantes.");
	}
}
