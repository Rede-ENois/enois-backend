package com.socialcoisa.rede.calls.adapters.out.net;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.socialcoisa.rede.calls.application.port.out.MeetingTokenIssuer;
import com.socialcoisa.rede.calls.domain.CallParticipant;
import com.socialcoisa.rede.calls.domain.CallSession;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Token de entrada para o JaaS (Jitsi as a Service, 8x8).
 *
 * <p>Escolhemos serviço gerenciado em vez de hospedar a infraestrutura: montar Jitsi self-hosted
 * exige JVB, Prosody, Jicofo, TURN e TLS próprios — um segundo sistema para operar e escalar — e
 * o meet.jit.si público não serve para produção (levantamento detalhado no README do módulo).
 *
 * <p>A chave privada do app JaaS fica numa variável de ambiente lida só no servidor; o navegador
 * recebe o JWT assinado. Sem chave configurada, {@link #isConfigured()} é falso e o serviço de
 * chamadas responde 503 em vez de abrir uma sala sem controle de acesso.
 */
@Component
class JaasMeetingTokenIssuer implements MeetingTokenIssuer {

	private static final Logger LOG = LoggerFactory.getLogger(JaasMeetingTokenIssuer.class);

	private final Clock clock;
	private final String appId;
	private final String keyId;
	private final String privateKeyPem;
	private final String host;
	private final Duration ttl;

	JaasMeetingTokenIssuer(Clock clock,
			@Value("${rede.calls.jaas.app-id:}") String appId,
			@Value("${rede.calls.jaas.key-id:}") String keyId,
			@Value("${rede.calls.jaas.private-key:}") String privateKeyPem,
			@Value("${rede.calls.jaas.host:8x8.vc}") String host,
			@Value("${rede.calls.jaas.token-ttl-minutes:120}") long ttlMinutes) {
		this.clock = clock;
		this.appId = appId == null ? "" : appId.trim();
		this.keyId = keyId == null ? "" : keyId.trim();
		this.privateKeyPem = privateKeyPem == null ? "" : privateKeyPem.trim();
		this.host = host == null || host.isBlank() ? "8x8.vc" : host.trim();
		this.ttl = Duration.ofMinutes(Math.max(1, ttlMinutes));
	}

	@Override
	public boolean isConfigured() {
		return !appId.isEmpty() && !keyId.isEmpty() && !privateKeyPem.isEmpty();
	}

	@Override
	public String host() {
		return host;
	}

	@Override
	public long tokenTtlSeconds() {
		return ttl.toSeconds();
	}

	@Override
	public String appId() {
		return appId.isEmpty() ? null : appId;
	}

	@Override
	public boolean includeAppIdInRoom() {
		return true;
	}

	@Override
	public String tokenFor(CallSession call, CallParticipant participant, String displayName, boolean moderator) {
		if (!isConfigured()) {
			throw new ApiException(503, "CALLS_NOT_CONFIGURED",
				"As chamadas ainda nao estao configuradas neste ambiente.");
		}
		Instant now = clock.instant();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
			.audience("jitsi")
			.issuer("chat")
			.subject(appId)
			// O nome da sala vai no token: trocar a URL não move a pessoa para outra chamada.
			.claim("room", roomName(call))
			.issueTime(Date.from(now))
			.notBeforeTime(Date.from(now.minusSeconds(30)))
			.expirationTime(Date.from(now.plus(ttl)))
			.claim("context", context(call, participant, displayName, moderator))
			.build();
		try {
			RSASSASigner signer = new RSASSASigner(signingKey());
			JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
				.keyID(keyId)
				.type(com.nimbusds.jose.JOSEObjectType.JWT)
				.build();
			SignedJWT jwt = new SignedJWT(header, claims);
			jwt.sign(signer);
			return jwt.serialize();
		}
		catch (JOSEException | GeneralSecurityException | IllegalArgumentException broken) {
			// Nunca logar a chave nem o token: o erro diz só que a credencial não foi aceita.
			LOG.error("Falha ao assinar o token de entrada na chamada: configuracao JaaS invalida", broken);
			throw new ApiException(503, "CALLS_NOT_CONFIGURED",
				"As chamadas estao temporariamente indisponiveis neste ambiente.");
		}
	}

	/**
	 * Chave de assinatura: o PEM é o formato que o JaaS entrega (PKCS#8). Lido aqui, no servidor,
	 * e nunca serializado de volta.
	 */
	private PrivateKey signingKey() throws GeneralSecurityException {
		String body = privateKeyPem
			.replace("-----BEGIN PRIVATE KEY-----", "")
			.replace("-----END PRIVATE KEY-----", "")
			.replaceAll("\\s", "");
		byte[] der = Base64.getDecoder().decode(body);
		return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
	}

	/** Nome da sala no JaaS: o id aleatório da chamada, nunca o apelido de alguém. */
	String roomName(CallSession call) {
		return appId + "/" + call.roomId();
	}

	private Map<String, Object> context(CallSession call, CallParticipant participant, String displayName,
			boolean moderator) {
		Map<String, Object> user = new LinkedHashMap<>();
		user.put("id", participant.userId());
		user.put("name", displayName == null || displayName.isBlank() ? "Convidado" : displayName);
		user.put("moderator", moderator);
		// Sem e-mail: o token não precisa carregar dado pessoal para a reunião funcionar.
		Map<String, Object> features = new LinkedHashMap<>();
		features.put("recording", false);
		features.put("livestreaming", false);
		features.put("transcription", false);
		features.put("outbound-call", false);
		features.put("sip-outbound-call", false);
		features.put("file-upload", false);
		features.put("list-visitors", false);
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("user", user);
		context.put("features", features);
		return context;
	}
}
