package com.socialcoisa.rede.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.shared.security.Hashes;
import com.socialcoisa.rede.shared.security.IpRateLimiter;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Captcha próprio para o cadastro quando o Cloudflare Turnstile não está configurado.
 *
 * É um desafio de soma assinado (HMAC) e com prazo curto. The answer only enters the HMAC input, so
 * the token carries nothing that can be checked offline against candidate answers; the client gets
 * only the question. Além da resposta certa, exigimos pelo menos {@value #MIN_ELAPSED_MS}ms desde a
 * emissão: uma soma dessas leva alguns segundos para um humano ler e digitar, e resolver na hora é
 * o sinal mais barato de script. O desafio também fica preso a quem o pediu — o hash do token de
 * sessão de quem emitiu e o da impressão do aparelho entram na assinatura — então um desafio
 * resolvido numa sessão não vale em outra, nem num aparelho diferente. O uso único é o mesmo nonce
 * gravado no banco, compartilhado por todos os pods.
 *
 * <p>ponytail: a sum question is readable by any bot; this is only a fallback. Production should
 * configure Turnstile (TURNSTILE_SITE_KEY / TURNSTILE_SECRET_KEY), which takes precedence.</p>
 *
 * O honeypot e o rate limit por IP continuam valendo em paralelo. Quando TURNSTILE_SITE_KEY e
 * TURNSTILE_SECRET_KEY existirem, o Turnstile assume e este desafio não é usado.
 */
@Service
public class SignupChallengeService {

	private static final Logger LOG = LoggerFactory.getLogger(SignupChallengeService.class);
	private static final Duration TTL = Duration.ofMinutes(5);
	/** Tempo mínimo entre emitir e responder: digitar a soma é trabalho de humano. */
	static final long MIN_ELAPSED_MS = 3_000L;
	/** Teto de desafios por origem — quem só pode mandar 10 cadastros por hora não precisa de 300. */
	static final String ISSUE_ACTION = IpRateLimiter.ISSUE_CHALLENGE;

	private static final int SESSION_BINDING_HEX = 16;

	private final boolean enabled;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();
	private final byte[] secret;
	private final JdbcTemplate jdbc;

	public SignupChallengeService(Clock clock, JdbcTemplate jdbc,
			@Value("${rede.security.signup-captcha-enabled:true}") boolean enabled,
			@Value("${rede.security.encryption-key:}") String encryptionKey) {
		this.enabled = enabled;
		this.clock = clock;
		this.jdbc = jdbc;
		this.secret = resolveSecret(encryptionKey, enabled);
	}

	public boolean isEnabled() {
		return enabled;
	}

	public Challenge issue() {
		return issue(null, null);
	}

	/**
	 * Emite o desafio preso a quem está pedindo: sessão (quando há) e impressão do aparelho. Os dois
	 * entram na assinatura, então quem recebe um desafio não consegue passá-lo para outra sessão.
	 */
	public Challenge issue(String sessionToken, String deviceFingerprint) {
		int left = 2 + random.nextInt(8);
		int right = 2 + random.nextInt(8);
		int answer = left + right;
		Instant now = clock.instant();
		Instant expiresAt = now.plus(TTL);
		String nonce = UUID.randomUUID().toString().replace("-", "");
		String payload = now.toEpochMilli() + "|" + expiresAt.toEpochMilli() + "|" + nonce;
		String binding = binding(sessionToken, deviceFingerprint);
		String token = encode(payload.getBytes(StandardCharsets.UTF_8)) + "."
			+ encode(sign(payload + "|" + binding, answer));
		return new Challenge(token, "Quanto é " + left + " + " + right + "?", expiresAt);
	}

	public void require(String challengeId, Integer answer) {
		require(challengeId, answer, null, null);
	}

	public void require(String challengeId, Integer answer, String sessionToken, String deviceFingerprint) {
		if (!enabled) {
			return;
		}
		if (!StringUtils.hasText(challengeId) || answer == null) {
			throw new ApiException(400, "SIGNUP_CAPTCHA_REQUIRED",
				"Resolva a verificação de segurança para criar a conta.");
		}
		String[] parts = challengeId.split("\\.");
		if (parts.length != 2) {
			throw invalid();
		}
		byte[] payloadBytes;
		byte[] signature;
		try {
			payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
			signature = Base64.getUrlDecoder().decode(parts[1]);
		}
		catch (IllegalArgumentException exception) {
			throw invalid();
		}
		String payload = new String(payloadBytes, StandardCharsets.UTF_8);
		// A wrong answer, a forged token and a desafio emitido para outra sessão/aparelho are
		// indistinguishable: all fail the HMAC.
		String signed = payload + "|" + binding(sessionToken, deviceFingerprint);
		if (!MessageDigest.isEqual(signature, sign(signed, answer))) {
			throw invalid();
		}

		String[] fields = payload.split("\\|");
		if (fields.length != 3) {
			throw invalid();
		}
		long issuedAt;
		long expiresAt;
		try {
			issuedAt = Long.parseLong(fields[0]);
			expiresAt = Long.parseLong(fields[1]);
		}
		catch (NumberFormatException exception) {
			throw invalid();
		}
		long now = clock.millis();
		if (expiresAt < now) {
			throw new ApiException(400, "SIGNUP_CAPTCHA_EXPIRED",
				"A verificação de segurança expirou. Recarregue e tente de novo.");
		}
		if (now - issuedAt < MIN_ELAPSED_MS) {
			throw new ApiException(400, "SIGNUP_CAPTCHA_TOO_FAST",
				"Calma aí: resolva a verificação com um tempinho e envie de novo.");
		}
		remember(fields[2], expiresAt, now);
	}

	/** The primary key makes the second use of a nonce fail on any pod. */
	private void remember(String nonce, long expiresAt, long now) {
		jdbc.update("delete from signup_challenge_nonces where expires_at < ?", new Timestamp(now));
		try {
			jdbc.update("insert into signup_challenge_nonces (nonce, expires_at) values (?, ?)", nonce,
				new Timestamp(expiresAt));
		}
		catch (DuplicateKeyException reused) {
			throw new ApiException(400, "SIGNUP_CAPTCHA_REUSED",
				"Esta verificação já foi usada. Recarregue e tente de novo.");
		}
	}

	private ApiException invalid() {
		return new ApiException(400, "SIGNUP_CAPTCHA_INVALID",
			"Verificação de segurança inválida. Recarregue e tente de novo.");
	}

	/**
	 * O que prende o desafio a quem o pediu. O token de sessão entra só como hash: o valor em claro
	 * nunca é assinado nem guardado, e um desafio de visitante (sem sessão) fica marcado como tal.
	 */
	private static String binding(String sessionToken, String deviceFingerprint) {
		String session = StringUtils.hasText(sessionToken)
			? Hashes.sha256Hex(sessionToken.trim()).substring(0, SESSION_BINDING_HEX) : "anon";
		String device = StringUtils.hasText(deviceFingerprint)
			? Hashes.sha256Hex(deviceFingerprint.trim()).substring(0, SESSION_BINDING_HEX) : "anon";
		return session + ":" + device;
	}

	private byte[] sign(String payload, int answer) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return mac.doFinal((payload + "|" + answer).getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception exception) {
			throw new IllegalStateException("HmacSHA256 indisponível", exception);
		}
	}

	private static String encode(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private static byte[] resolveSecret(String encryptionKey, boolean enabled) {
		if (StringUtils.hasText(encryptionKey)) {
			try {
				byte[] decoded = Base64.getDecoder().decode(encryptionKey.trim());
				if (decoded.length >= 16) {
					return decoded;
				}
			}
			catch (IllegalArgumentException ignored) {
				// Chave fora do formato base64: tratada abaixo.
			}
		}
		if (enabled) {
			// Chave efêmera por pod quebra o desafio entre instâncias e é previsível para o atacante
			// que compartilha o processo? Não: é aleatória, mas o desafio de um pod não vale no
			// outro. Em produção, exija a chave estável (E-36).
			throw new IllegalStateException(
				"REDE_SECURITY_ENCRYPTION_KEY obrigatoria para o desafio de cadastro (32 bytes em base64).");
		}
		byte[] generated = new byte[32];
		new SecureRandom().nextBytes(generated);
		LOG.warn("Desafio de cadastro desligado sem chave de criptografia: usando segredo efêmero.");
		return generated;
	}

	public record Challenge(String challengeId, String question, Instant expiresAt) {
	}
}
