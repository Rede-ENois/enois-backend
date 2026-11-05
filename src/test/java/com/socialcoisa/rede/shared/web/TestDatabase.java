package com.socialcoisa.rede.shared.web;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.shared.security.Hashes;

/**
 * Acesso direto ao banco dos testes.
 *
 * Existe para os testes montarem o estado que querem observar (ex.: conta ja com e-mail confirmado)
 * sem depender de um token que so existe no e-mail enviado. Nada aqui e usado pela aplicacao.
 */
public final class TestDatabase {

	private static JdbcTemplate jdbcTemplate;

	private TestDatabase() {
	}

	static void install(JdbcTemplate template) {
		jdbcTemplate = template;
	}

	public static void markEmailVerified(String userId, Instant at) {
		require().update("update users set email_verified_at = ? where id = ?", at, userId);
	}

	/** Promove a conta no banco (o papel admin é imutável e não vem de e-mail/username). */
	public static void setAdmin(String userId) {
		require().update("update users set is_admin = true where id = ?", userId);
	}

	/** Devolve a conta ao estado "sem e-mail confirmado" (para testar a regra de credibilidade). */
	public static void clearEmailVerification(String userId) {
		require().update("update users set email_verified_at = null where id = ?", userId);
	}

	/** Conta anterior a exigencia da data de nascimento: o portao de idade barra a escrita dela. */
	public static void clearBirthDate(String userId) {
		require().update("update users set birth_date = null where id = ?", userId);
	}

	/** Liga um segundo fator sem passar pelo fluxo, para cenarios que so precisam do estado final. */
	public static void enableTwoFactor(String userId, Instant at) {
		require().update("update users set totp_enabled_at = ? where id = ?", at, userId);
	}

	/**
	 * Devolve a conta ao estado de quem entrou por login social: tem hash, mas a pessoa nao conhece
	 * a senha. E o estado que o cadastro por provedor cria (`password_usable = false`).
	 */
	public static void makePasswordUnusable(String userId) {
		require().update("update user_credentials set password_usable = false where user_id = ?", userId);
	}


	/** Insere um token de confirmacao com o hash de um valor que o teste conhece. */
	public static void insertVerificationToken(String rawToken, String userId, Instant expiresAt, Instant now) {
		require().update("""
				insert into email_verification_tokens (token, user_id, expires_at, used_at, created_at)
				values (?, ?, ?, null, ?)""", sha256(rawToken), userId, expiresAt, now);
	}

	public static void insertExternalIdentity(String userId, String provider, String externalUserId, String email) {
		require().update("""
				insert into user_external_identities (user_id, provider, external_user_id, email, created_at)
				values (?, ?, ?, ?, ?)""", userId, provider, externalUserId, email, Instant.now());
	}

	/** Registra um pedido de autorizacao OAuth (o callback so aceita state que existe). */
	public static void insertOAuthRequest(String state, String provider, String nonce, Instant expiresAt) {
		require().update("""
				insert into oauth_authorization_requests (state, provider, nonce, user_id, expires_at, created_at)
				values (?, ?, ?, null, ?, ?)""", state, provider, nonce, expiresAt, Instant.now());
	}

	/**
	 * Insere uma vaga direto no banco. Os testes de busca precisam de um acervo controlado e nao
	 * podem passar pelo gate de reputacao (>150) do POST /jobs.
	 */
	public static void insertJob(String id, String title, String company, String location, String type, String level,
			List<String> skills, Instant postedAt) {
		// Cada teste re-semeia o mesmo acervo: limpa antes para o id nao colidir.
		require().update("delete from job_skills where job_id = ?", id);
		require().update("delete from jobs where id = ?", id);
		require().update("""
				insert into jobs (id, title, company, location, type, level, salary, currency, posted_by,
					posted_at, updated_at, community_agreed_at)
				values (?, ?, ?, ?, ?, ?, null, 'BRL', 'user_job_seed', ?, ?, ?)""",
			id, title, company, location, type, level, postedAt, postedAt, postedAt);
		for (String skill : skills) {
			require().update("insert into job_skills (job_id, skill) values (?, ?)", id, skill);
		}
	}

	/** Define o dono de uma vaga inserida direto no banco. */
	public static void setJobPostedBy(String jobId, String userId) {
		require().update("update jobs set posted_by = ? where id = ?", userId, jobId);
	}

	/**
	 * Insere uma vaga EXTERNA (agregada de uma plataforma) direto no banco. O diretório de
	 * empresas junta as duas origens, e só a externa carrega a origem "externa".
	 */
	public static void insertExternalJob(String id, String title, String company, String location,
			String url, Instant importedAt) {
		require().update("delete from external_jobs where id = ?", id);
		require().update("""
				insert into external_jobs (id, platform, external_id, title, company, location, salary,
					currency, url, imported_at, last_checked_at, external_status)
				values (?, 'Plataforma Teste', ?, ?, ?, ?, null, 'BRL', ?, ?, ?, 'FOUND')""",
			id, id + "-externo", title, company, location, url, importedAt, importedAt);
	}

	/** Insere uma candidatura direto no banco (para reproduzir uma candidatura orfa do dono). */
	public static void insertJobApplication(String jobId, String userId) {
		require().update("delete from job_applications where job_id = ? and user_id = ?", jobId, userId);
		require().update("""
				insert into job_applications (job_id, user_id, status, applied_at)
				values (?, ?, 'APPLIED', ?)""", jobId, userId, java.time.Instant.now());
	}

	/** Forca a expiracao da sessao de um access token (para exercitar refresh sem esperar o tempo). */
	public static void expireAccessToken(String accessToken, Instant expiresAt) {
		require().update("update auth_sessions set expires_at = ? where token = ?", expiresAt,
			Hashes.sha256Hex(accessToken));
	}

	/** Forca a expiracao do refresh token de uma sessao. */
	public static void expireRefreshToken(String refreshToken, Instant refreshExpiresAt) {
		require().update("update auth_sessions set refresh_expires_at = ? where refresh_token = ?",
			refreshExpiresAt, Hashes.sha256Hex(refreshToken));
	}

	/**
	 * Apaga uma comunidade criada pelo teste. O banco dos testes e compartilhado entre as classes,
	 * entao deixar uma comunidade para tras quebra o contrato que espera a lista vazia.
	 */
	public static void deleteCommunity(String communityId) {
		require().update("delete from community_members where community_id = ?", communityId);
		require().update("delete from communities where id = ?", communityId);
	}

	/** Muda a data de um evento direto no banco (para testar vencimento sem esperar o tempo passar). */
	public static void setEventDate(String eventId, String date, String time) {
		require().update("update events set date = ?, time = ? where id = ?", date, time, eventId);
	}

	/** Marca a sanitizacao como antiga, para exercitar o expurgo por retencao. */
	public static void setEventSanitizedAt(String eventId, Instant sanitizedAt) {
		require().update("update events set sanitized_at = ? where id = ?", sanitizedAt, eventId);
	}

	public static int countEventAttendees(String eventId) {
		Integer count = require().queryForObject("select count(*) from event_attendees where event_id = ?",
			Integer.class, eventId);
		return count == null ? 0 : count;
	}

	public static Optional<String> userIdByEmail(String email) {
		return require().query("select id from users where email = ?", (rs, row) -> rs.getString(1), email)
			.stream()
			.findFirst();
	}

	public static String sha256(String value) {
		try {
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		}
		catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	/**
	 * Troca o token de um cadastro pendente por um valor que o teste conhece (o banco guarda o
	 * hash do token; o token cru so existe no e-mail).
	 */
	public static void setPendingSignupToken(String email, String rawToken) {
		require().update("update pending_signups set token_hash = ? where email = ?", sha256(rawToken), email);
	}

	/** Troca o token do convite parental por um valor que o teste conhece (o banco guarda o hash). */
	public static void setParentalLinkToken(String guardianEmail, String rawToken) {
		require().update("update parental_links set token_hash = ? where guardian_email = ? and status = 'PENDING'",
			sha256(rawToken), guardianEmail);
	}

	/** Vence o link de um cadastro pendente (para testar o 410 sem esperar o TTL). */
	public static void expirePendingSignup(String email) {
		require().update("update pending_signups set expires_at = ? where email = ?",
			Instant.now().minusSeconds(60), email);
	}

	public static int pendingSignupsByEmail(String email) {
		Integer count = require().queryForObject("select count(*) from pending_signups where email = ?",
			Integer.class, email);
		return count == null ? 0 : count;
	}

	private static JdbcTemplate require() {
		if (jdbcTemplate == null) {
			throw new IllegalStateException("TestDatabase nao foi inicializado (TestDatabaseSupport ausente).");
		}
		return jdbcTemplate;
	}
}