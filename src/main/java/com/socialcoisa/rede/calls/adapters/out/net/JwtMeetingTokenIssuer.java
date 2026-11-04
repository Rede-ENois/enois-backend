package com.socialcoisa.rede.calls.adapters.out.net;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.socialcoisa.rede.calls.application.port.out.MeetingTokenIssuer;
import com.socialcoisa.rede.calls.domain.CallParticipant;
import com.socialcoisa.rede.calls.domain.CallSession;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Token de entrada para o Jitsi que a gente hospeda (docker-jitsi-meet com {@code AUTH_TYPE=jwt}).
 *
 * <p>O Jitsi recusa qualquer entrada sem token válido ({@code ENABLE_AUTH=1},
 * {@code ENABLE_GUESTS=0}); o Prosody confere a assinatura HS256 com o mesmo segredo que está na
 * variável de ambiente do servidor — nada de configuração por app nem de conta em terceiros. O
 * segredo nunca sai daqui: o navegador recebe o token assinado e nada mais.
 *
 * <p>Sem endereço ou segredo configurados, {@link #isConfigured()} é falso e o serviço responde
 * 503 em vez de apontar o painel para uma instância qualquer sem controle de acesso.
 */
@Component
class JwtMeetingTokenIssuer implements MeetingTokenIssuer {

	private static final Logger LOG = LoggerFactory.getLogger(JwtMeetingTokenIssuer.class);

	/** O plugin do Prosody só aceita HS256; o segredo precisa ser largo o bastante para ele. */
	private static final int MIN_SECRET_BYTES = 32;

	/**
	 * Domínio do Jitsi como o Prosody o conhece (o mesmo {@code VirtualHost} da configuração dele,
	 * não a URL pública). O plugin de token recusa token sem {@code aud} quando a lista de
	 * audiências aceitas está preenchida.
	 */
	static final String DEFAULT_AUDIENCE = "meet.jitsi";

	private final Clock clock;
	private final String url;
	private final String secret;
	private final String issuer;
	private final String audience;
	private final Duration ttl;

	JwtMeetingTokenIssuer(Clock clock,
			@Value("${rede.calls.jitsi.url:}") String url,
			@Value("${rede.calls.jitsi.jwt-secret:}") String secret,
			@Value("${rede.calls.jitsi.jwt-issuer:rede}") String issuer,
			@Value("${rede.calls.jitsi.jwt-audience:" + DEFAULT_AUDIENCE + "}") String audience,
			@Value("${rede.calls.jitsi.token-ttl-minutes:120}") long ttlMinutes) {
		this.clock = clock;
		this.url = normalize(url);
		this.secret = (secret == null ? "" : secret).trim();
		this.issuer = blankTo(issuer, "rede");
		this.audience = blankTo(audience, DEFAULT_AUDIENCE);
		this.ttl = Duration.ofMinutes(Math.max(1, ttlMinutes));
	}

	@Override
	public boolean isConfigured() {
		return url != null && usableSecret();
	}

	@Override
	public String host() {
		if (!isConfigured()) {
			throw notConfigured("As chamadas ainda nao estao configuradas neste ambiente.");
		}
		return url;
	}

	@Override
	public long tokenTtlSeconds() {
		return ttl.toSeconds();
	}

	@Override
	public String tokenFor(CallSession call, CallParticipant participant, String displayName, boolean moderator) {
		if (!isConfigured()) {
			throw notConfigured("As chamadas ainda nao estao configuradas neste ambiente.");
		}
		Instant now = clock.instant();
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
			.issuer(issuer)
			// O nome da sala vai no token: o Jitsi usa este valor, não o caminho da URL, e trocar
			// a URL no painel não move a pessoa para outra chamada.
			.claim("room", call.roomId())
			.subject(call.roomId())
			.issueTime(Date.from(now))
			.notBeforeTime(Date.from(now.minusSeconds(30)))
			.expirationTime(Date.from(now.plus(ttl)))
			.claim("context", context(participant, displayName, moderator))
			.audience(audience);
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
				.type(JOSEObjectType.JWT)
				.build(), claims.build());
			jwt.sign(new MACSigner(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
			return jwt.serialize();
		}
		catch (JOSEException | IllegalArgumentException broken) {
			// Nunca logar o segredo nem o token: o erro diz só que a credencial não foi aceita.
			LOG.error("Falha ao assinar o token de entrada na chamada: configuracao do Jitsi invalida", broken);
			throw notConfigured("As chamadas estao temporariamente indisponiveis neste ambiente.");
		}
	}

	private Map<String, Object> context(CallParticipant participant, String displayName, boolean moderator) {
		Map<String, Object> user = new LinkedHashMap<>();
		user.put("id", participant.userId());
		user.put("name", displayName == null || displayName.isBlank() ? "Convidado" : displayName);
		user.put("moderator", moderator);
		// Sem e-mail: o token não precisa carregar dado pessoal para a reunião funcionar.
		user.put("features", Map.of(
			"recording", false,
			"livestreaming", false,
			"transcription", false,
			"outbound-call", false));
		// A lista de moderadores é do plugin do Prosody; vai explícita para o Jitsi saber quem é
		// quem sem confiar no que a tela mandou.
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("user", user);
		context.put("moderator", moderator);
		context.put("room", Map.of("moderator", false));
		context.put("features", Map.of(
			"recording", false,
			"livestreaming", false,
			"transcription", false));
		return context;
	}

	/** URL nunca com barra no fim: o cliente monta {@code host/room}. */
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

	/** Segredo curto não é aceito como configurado: o Jitsi exigiria outro e a sala não abriria. */
	private boolean usableSecret() {
		return secret.getBytes(StandardCharsets.UTF_8).length >= MIN_SECRET_BYTES;
	}

	private static ApiException notConfigured(String message) {
		return new ApiException(503, "CALLS_NOT_CONFIGURED", message);
	}

	private static String blankTo(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}
}
