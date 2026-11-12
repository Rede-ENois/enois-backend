package com.socialcoisa.rede.calendar.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

import com.socialcoisa.rede.shared.security.Hashes;

/**
 * O vínculo entre a agenda de uma pessoa e o token da assinatura `.ics` privada (fase 9, D7).
 *
 * <p>O token é a única credencial do link: quem o tem lê a agenda inteira, sem sessão. Por isso ele
 * nunca é guardado em claro — o banco tem só o hash SHA-256 — e a resposta da rota de gestão devolve
 * o token uma única vez, no momento em que nasce ou é regenerado. Regenerar mata o antigo: o
 * calendário externo que assinou para de atualizar na hora, sem esperar ciclo.
 *
 * <p>O recorte (`scope`) fica congelado no token: a assinatura é de uma agenda específica
 * (social, profissional ou as duas), e mudar o recorte na tela é regenerar o link — não é um
 * parâmetro da URL que quem assinou pode trocar.
 */
public class CalendarSubscription {

	/** 32 bytes de aleatório → 43 caracteres de base64url: longo o bastante para força bruta não pagar. */
	public static final int TOKEN_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	private final String id;
	private final String userId;
	private final CalendarScope scope;
	private final String tokenHash;
	private final Instant createdAt;
	private Instant revokedAt;

	public CalendarSubscription(String id, String userId, CalendarScope scope, String tokenHash,
			Instant createdAt, Instant revokedAt) {
		this.id = Objects.requireNonNull(id, "id");
		this.userId = Objects.requireNonNull(userId, "dono");
		// Nulo é a agenda consolidada, o mesmo contrato da leitura: `scope` ausente devolve as duas.
		this.scope = scope;
		this.tokenHash = requireHash(tokenHash);
		this.createdAt = Objects.requireNonNull(createdAt, "criação");
		this.revokedAt = revokedAt;
	}

	/** Nasce ativa, com o hash do token passado (o token em claro nunca entra no domínio). */
	public static CalendarSubscription create(String id, String userId, CalendarScope scope,
			String tokenHash, Instant now) {
		return new CalendarSubscription(id, userId, scope, tokenHash, now, null);
	}

	/** Token novo, para regeneração: outro `SecureRandom` por chamada, sem estado compartilhado. */
	public static String newToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public String id() {
		return id;
	}

	public String userId() {
		return userId;
	}

	public CalendarScope scope() {
		return scope;
	}

	public String tokenHash() {
		return tokenHash;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant revokedAt() {
		return revokedAt;
	}

	public boolean active() {
		return revokedAt == null;
	}

	/** O hash do token informado bate com o guardado? Comparação em tempo constante. */
	public boolean matches(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return false;
		}
		byte[] expected = tokenHash.getBytes(StandardCharsets.US_ASCII);
		byte[] actual = Hashes.sha256Hex(rawToken).getBytes(StandardCharsets.US_ASCII);
		return MessageDigest.isEqual(expected, actual);
	}

	/**
	 * Revoga na hora. Revogar já revogada é idempotente: a revogação primeira é a que vale, porque
	 * é ela que o leitor externo já viu falhar.
	 */
	public void revoke(Instant now) {
		if (revokedAt == null) {
			revokedAt = now;
		}
	}

	private static String requireHash(String tokenHash) {
		Objects.requireNonNull(tokenHash, "hash do token");
		if (tokenHash.length() != 64 || !tokenHash.chars().allMatch(c -> Character.isDigit(c)
				|| (c >= 'a' && c <= 'f'))) {
			throw new IllegalArgumentException("hash do token precisa ser SHA-256 em hex");
		}
		return tokenHash;
	}
}
