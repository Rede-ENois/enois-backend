package com.socialcoisa.rede.identity.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.identity.application.port.in.ChangeEmailUseCase;
import com.socialcoisa.rede.identity.application.port.in.ChangePasswordUseCase;
import com.socialcoisa.rede.identity.application.port.in.DeleteAccountUseCase;
import com.socialcoisa.rede.identity.application.port.in.GetAuthenticatedUserUseCase;
import com.socialcoisa.rede.identity.application.port.in.LoginUseCase;
import com.socialcoisa.rede.identity.application.port.in.LogoutUseCase;
import com.socialcoisa.rede.identity.application.port.in.RefreshSessionUseCase;
import com.socialcoisa.rede.identity.application.port.in.RequestPasswordResetUseCase;
import com.socialcoisa.rede.identity.application.port.in.ResetPasswordUseCase;
import com.socialcoisa.rede.identity.application.port.in.SignupUseCase;
import com.socialcoisa.rede.identity.application.port.in.TwoFactorLoginUseCase;
import com.socialcoisa.rede.identity.application.port.in.VerifyEmailUseCase;
import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.identity.application.port.out.CredentialRepository;
import com.socialcoisa.rede.identity.application.port.out.EmailVerificationTokenRepository;
import com.socialcoisa.rede.identity.application.port.out.PasswordResetTokenRepository;
import com.socialcoisa.rede.identity.application.port.out.PendingSignupRepository;
import com.socialcoisa.rede.identity.application.port.out.StoredCredential;
import com.socialcoisa.rede.identity.application.port.out.UserExternalIdentityRepository;
import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.identity.domain.EmailVerificationToken;
import com.socialcoisa.rede.identity.domain.PasswordResetToken;
import com.socialcoisa.rede.identity.domain.PendingSignup;
import com.socialcoisa.rede.identity.domain.TwoFactorChallenge;
import com.socialcoisa.rede.identity.domain.UsernameSlug;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class AuthService implements SignupUseCase, LoginUseCase, LogoutUseCase, GetAuthenticatedUserUseCase,
		RequestPasswordResetUseCase, ResetPasswordUseCase, RefreshSessionUseCase, ChangeEmailUseCase,
		ChangePasswordUseCase, DeleteAccountUseCase, VerifyEmailUseCase, TwoFactorLoginUseCase {

	private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);


	private static final Duration RESET_TOKEN_TTL = Duration.ofHours(24);

	private static final int MAX_PASSWORD_LENGTH = 128;
	private static final java.util.Set<String> COMMON_PASSWORDS = loadCommonPasswords();

	/** Validade do link de confirmacao de e-mail. */
	private static final Duration VERIFICATION_TOKEN_TTL = Duration.ofHours(24);

	/** Janela do limite de reenvio do link de confirmacao. */
	private static final Duration VERIFICATION_RESEND_WINDOW = Duration.ofHours(1);
	private static final int VERIFICATION_RESEND_LIMIT = 3;

	private final Duration accessTokenTtl;
	private final Duration refreshTokenTtl;
	private final UserProfileRepository userProfileRepository;
	private final CredentialRepository credentialRepository;
	private final AuthSessionRepository authSessionRepository;
	private final PasswordResetTokenRepository passwordResetTokenRepository;
	private final EmailVerificationTokenRepository emailVerificationTokenRepository;
	private final PendingSignupRepository pendingSignups;
	private final TwoFactorService twoFactorService;
	private final UserExternalIdentityRepository externalIdentityRepository;
	private final PasswordHasher passwordHasher;
	private final EmailService emailService;
	private final Clock clock;
	private volatile String dummyPasswordHash;

	private final com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate;
	private final org.springframework.jdbc.core.JdbcTemplate jdbc;
	private final com.socialcoisa.rede.shared.security.IpRateLimiter rateLimiter;
	private final SecureRandom secureRandom = new SecureRandom();

	public AuthService(@Value("${rede.auth.access-token-ttl-minutes:60}") int accessTokenTtlMinutes,
                       @Value("${rede.auth.refresh-token-ttl-days:30}") int refreshTokenTtlDays,
                       UserProfileRepository userProfileRepository, CredentialRepository credentialRepository,
                       AuthSessionRepository authSessionRepository, PasswordResetTokenRepository passwordResetTokenRepository,
                       EmailVerificationTokenRepository emailVerificationTokenRepository,
                       PendingSignupRepository pendingSignups,
                       TwoFactorService twoFactorService,
                       UserExternalIdentityRepository externalIdentityRepository,
                       PasswordHasher passwordHasher, EmailService emailService, Clock clock,
                       com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate,
                       org.springframework.jdbc.core.JdbcTemplate jdbc,
                       com.socialcoisa.rede.shared.security.IpRateLimiter rateLimiter) {
		this.accessTokenTtl = Duration.ofMinutes(accessTokenTtlMinutes);
		this.refreshTokenTtl = Duration.ofDays(refreshTokenTtlDays);
		this.userProfileRepository = userProfileRepository;
		this.credentialRepository = credentialRepository;
		this.authSessionRepository = authSessionRepository;
		this.passwordResetTokenRepository = passwordResetTokenRepository;
		this.emailVerificationTokenRepository = emailVerificationTokenRepository;
		this.pendingSignups = pendingSignups;
		this.twoFactorService = twoFactorService;
		this.externalIdentityRepository = externalIdentityRepository;
		this.passwordHasher = passwordHasher;
        this.emailService = emailService;
        this.clock = clock;
		this.sanctionGate = sanctionGate;
		this.jdbc = jdbc;
		this.rateLimiter = rateLimiter;
	}

	@Override
	@Transactional
	public SignupResult signup(SignupCommand command) {
		validateSignup(command);
		LocalDate birthDate = parseBirthDate(command.birthDate());
		String email = command.email().trim().toLowerCase(Locale.ROOT);
		var existing = userProfileRepository.findByEmail(email)
			.or(() -> userProfileRepository.findByEmail(canonicalEmail(email)));
		if (existing.isPresent() || credentialRepository.existsByEmail(email)) {
			// Same response as a new signup, so the endpoint does not reveal registered addresses.
			// The account owner gets a notice instead of a verification link. The discarded hash
			// keeps the latency of both paths alike.
			passwordHasher.hash(command.password());
			// Capped per account so repeated signups cannot flood the owner's inbox.
			boolean sent = existing
				.filter(profile -> rateLimiter.tryAcquire(com.socialcoisa.rede.shared.security.IpRateLimiter.EMAIL,
					"signup-notice:" + profile.id()))
				.map(profile -> emailService.sendSignupAttemptNotice(profile.email(), profile.name()))
				.orElse(true);
			LOG.info("auth_signup_existing_account emailSent={}", sent);
			return new SignupResult(email, true, sent);
		}
		Instant now = clock.instant();
		// Duas etapas: NADA de conta (users/credentials) nasce aqui. Guardamos o cadastro com o
		// hash da senha e mandamos o link; a conta so existe quando o e-mail for confirmado — e
		// assim um cadastro em massa com e-mail invalido nao polui a base.
		pendingSignups.deleteExpired(now);
		pendingSignups.deleteByEmail(email);
		String rawToken = newVerificationToken();
		String name = command.name().trim();
		// O e-mail do cadastro conta como o 1o envio da janela: sobram 2 reenvios, como no
		// fluxo de conta ja criada.
		PendingSignup pending = new PendingSignup(
			"pending_" + UUID.randomUUID().toString().replace("-", ""),
			email, name, birthDate, passwordHasher.hash(command.password()),
			hashToken(rawToken), now.plus(VERIFICATION_TOKEN_TTL), now, 1, now);
		pendingSignups.save(pending);
		boolean emailSent = emailService.sendVerificationEmail(email, name, rawToken);
		LOG.info("auth_signup_pending emailSent={}", emailSent);
		return new SignupResult(email, true, emailSent);
	}

	/**
	 * Valida a data de nascimento declarada (ECA Digital): formato ISO, data real, nao-futura e
	 * idade plausivel (0 a 120 anos). Recusa null — cadastro sem data nao existe mais.
	 */
	static LocalDate parseBirthDate(String value) {
		if (value == null || value.isBlank()) {
			throw new ApiException(400, "BIRTH_DATE_REQUIRED",
				"Informe a data de nascimento para criar a conta.");
		}
		LocalDate date;
		try {
			date = LocalDate.parse(value.trim());
		}
		catch (Exception e) {
			throw new ApiException(400, "INVALID_BIRTH_DATE", "Data de nascimento invalida (use aaaa-mm-dd).");
		}
		LocalDate today = LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"));
		if (date.isAfter(today)) {
			throw new ApiException(400, "INVALID_BIRTH_DATE", "A data de nascimento nao pode ser no futuro.");
		}
		int age = java.time.Period.between(date, today).getYears();
		if (age < 0 || age > 120) {
			throw new ApiException(400, "INVALID_BIRTH_DATE", "A data de nascimento nao parece real.");
		}
		return date;
	}

	@Override
	@Transactional
	public LoginResult login(LoginCommand command) {
		validateLogin(command);
		String email = command.email().trim().toLowerCase(Locale.ROOT);
		var found = credentialRepository.findByEmail(email);
		if (found.isEmpty()) {
			// PBKDF2 fictício: sem isso, e-mail inexistente respondia em ~33 ms e existente em
			// ~650 ms, permitindo enumerar contas pela latência (E-14/EN-12).
			// Cadastro de duas etapas: a conta ainda NAO existe, mas a pessoa ja se cadastrou e a
			// senha dela esta certa. Responder "senha invalida" seria mentira (e a pessoa nao
			// entenderia o que fazer) — o certo e pedir a confirmacao do e-mail. Only with the right
			// password: otherwise the answer would reveal which addresses have a pending signup.
			var pending = pendingSignups.findByEmail(email)
				.filter(signup -> !signup.isExpired(clock.instant()));
			boolean pendingPasswordMatches = passwordHasher.matches(command.password(),
				pending.map(PendingSignup::passwordHash).orElseGet(this::dummyPasswordHash));
			if (pending.isPresent() && pendingPasswordMatches) {
				LOG.warn("auth_login_failed reason=email_not_verified");
				throw new ApiException(403, "EMAIL_NOT_VERIFIED",
					"Confirme o e-mail que enviamos para ativar a conta.");
			}
			LOG.warn("auth_login_failed reason=unknown_email");
			throw new ApiException(401, "INVALID_CREDENTIALS", "Email ou senha invalidos.");
		}
		StoredCredential credential = found.get();
		if (!passwordHasher.matches(command.password(), credential.passwordHash())) {
			LOG.warn("auth_login_failed reason=invalid_password");
			throw new ApiException(401, "INVALID_CREDENTIALS", "Email ou senha invalidos.");
		}
		if (credential.resetNeeded()) {
			LOG.info("auth_login_reset_needed userId={}", credential.userId());
			String rawToken = newResetToken();
			Instant now = clock.instant();
			passwordResetTokenRepository.deleteByUserId(credential.userId());
			passwordResetTokenRepository.save(
				com.socialcoisa.rede.identity.domain.PasswordResetToken.create(
					hashToken(rawToken), credential.userId(), now.plus(RESET_TOKEN_TTL), now));
			return LoginResult.passwordExpired(rawToken);
		}
		UserProfile profile = userProfileRepository.findById(credential.userId())
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		requireVerifiedIdentity(profile);
		// Ban (recurso negado) trava ate o login: a conta nao emite sessao nova.
		if (sanctionGate.banned(profile.id())) {
			LOG.warn("auth_login_blocked reason=account_banned userId={}", profile.id());
			throw new ApiException(403, "ACCOUNT_BANNED",
				"Esta conta foi banida apos revisao do recurso. Fale com o suporte se precisar.");
		}

		// Senha correta e o primeiro passo: com segundo fator ativo, aqui sai um desafio, nao sessao.
		var requiredMethod = twoFactorService.requiredMethod(profile.id());
		if (requiredMethod.isPresent()) {
			LOG.info("auth_login_two_factor_required userId={} method={}", profile.id(), requiredMethod.get());
			TwoFactorChallenge challenge = twoFactorService.startChallenge(profile.id(), requiredMethod.get());
			return LoginResult.twoFactorRequired(challenge.id(), challenge.method().name(), challenge.expiresAt());
		}
		LOG.info("auth_login_success userId={}", profile.id());
		return LoginResult.authenticated(createResponse(profile, command.ip()));
	}

	/** Conclui o login com o codigo do segundo fator (ou um codigo de recuperacao). */
	@Override
	@Transactional
	public AuthResponse verifyTwoFactor(String challengeId, String code) {
		if (!StringUtils.hasText(challengeId)) {
			throw new ApiException(400, "TWO_FACTOR_CHALLENGE_NOT_FOUND", "Informe o desafio do login.");
		}
		String userId = twoFactorService.verifyChallenge(challengeId.trim(), code);
		UserProfile profile = userProfileRepository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		if (sanctionGate.banned(profile.id())) {
			throw new ApiException(403, "ACCOUNT_BANNED",
				"Esta conta foi banida apos revisao do recurso. Fale com o suporte se precisar.");
		}
		LOG.info("auth_login_success userId={} via=2fa", profile.id());
		return createResponse(profile, null);
	}

	@Override
	public void resendTwoFactorCode(String challengeId) {
		twoFactorService.resendTwoFactorCode(challengeId);
	}

	@Override
	@Transactional
	public AuthResponse verifyEmail(String token) {
		if (!StringUtils.hasText(token)) {
			throw new ApiException(400, "INVALID_TOKEN", "Token de confirmacao obrigatorio.");
		}
		String tokenHash = hashToken(token.trim());
		// Cadastro em duas etapas: o link confirma o e-mail E cria a conta (que ainda nao existia).
		var pending = pendingSignups.findByTokenHash(tokenHash);
		if (pending.isPresent()) {
			PendingSignup signup = pending.get();
			Instant now = clock.instant();
			if (signup.isExpired(now)) {
				// Nao apagamos aqui: a transacao vai reverter com o 410. O pendente vencido e
				// removido na proxima limpeza oportunista (novo cadastro ou reenvio).
				throw new ApiException(410, "TOKEN_EXPIRED", "Link de confirmacao expirado. Peca um novo.");
			}
			// Corrida rara: a mesma pessoa pode ter criado a conta por outro caminho nesse meio-tempo.
			if (userProfileRepository.existsByEmail(signup.email()) || credentialRepository.existsByEmail(signup.email())) {
				pendingSignups.deleteByEmail(signup.email());
				throw new ApiException(409, "EMAIL_ALREADY_REGISTERED", "Email ja cadastrado.");
			}
			String userId = "user_" + UUID.randomUUID().toString().replace("-", "");
			String username = uniqueUsername(signup.name(), signup.email());
			// Nao existe papel no schema; o headline fica vazio ate o usuario preencher.
			UserProfile profile = UserProfile.create(userId, username, signup.email(), signup.name(), null,
				signup.createdAt());
			profile.acceptTerms(now);
			// ECA Digital: a idade vem da data declarada no cadastro, validada no servidor.
			profile.declareBirthDate(signup.birthDate(), now);
			// O e-mail acabou de ser provado: a conta nasce verificada e ja emite sessao.
			profile.markEmailVerified(now);
			userProfileRepository.save(profile);
			credentialRepository.save(userId, signup.email(), signup.passwordHash());
			pendingSignups.deleteByEmail(signup.email());
			return createResponse(profile, null);
		}
		EmailVerificationToken verification = emailVerificationTokenRepository.findByToken(tokenHash)
			.orElseThrow(() -> new ApiException(404, "INVALID_TOKEN", "Link de confirmacao invalido."));
		if (verification.isUsed()) {
			throw new ApiException(409, "TOKEN_ALREADY_USED", "Este link de confirmacao ja foi usado.");
		}
		if (verification.isExpired(clock.instant())) {
			throw new ApiException(410, "TOKEN_EXPIRED", "Link de confirmacao expirado. Peca um novo.");
		}
		UserProfile profile = userProfileRepository.findById(verification.userId())
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		Instant now = clock.instant();
		if (!profile.emailVerified()) {
			profile.markEmailVerified(now);
			userProfileRepository.save(profile);
		}
		// Uso unico: consome o token antes de emitir a sessao.
		emailVerificationTokenRepository.markUsed(tokenHash);
		return createResponse(profile, null);
	}

	@Override
	@Transactional
	public void resendVerification(String email) {
		if (!StringUtils.hasText(email) || !email.contains("@")) {
			throw new ApiException(400, "INVALID_EMAIL", "Email invalido.");
		}
		String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
		// Resposta neutra: nao confirmamos se o e-mail existe (evita enumeracao de contas).
		userProfileRepository.findByEmail(normalizedEmail)
			.filter(profile -> !profile.emailVerified())
			.ifPresent(profile -> {
				Instant now = clock.instant();
				if (emailVerificationTokenRepository.countByUserIdSince(profile.id(),
						now.minus(VERIFICATION_RESEND_WINDOW)) >= VERIFICATION_RESEND_LIMIT) {
					throw new ApiException(429, "TOO_MANY_REQUESTS",
						"Limite de reenvios atingido. Tente novamente mais tarde.");
				}
				issueVerificationToken(profile, now);
			});
		// Cadastro de duas etapas: ainda nao ha conta, mas o pendente pode reenviar o link — com o
		// MESMO teto do fluxo de conta (3 envios por hora). Sem o contador, o reenvio nao tinha
		// limite: dava para spammar a caixa de quem se cadastrou.
		pendingSignups.findByEmail(normalizedEmail).ifPresent(pending -> {
			Instant now = clock.instant();
			if (pending.sendsWithin(now, VERIFICATION_RESEND_WINDOW) >= VERIFICATION_RESEND_LIMIT) {
				throw new ApiException(429, "TOO_MANY_REQUESTS",
					"Limite de reenvios atingido. Tente novamente mais tarde.");
			}
			String rawToken = newVerificationToken();
			pendingSignups.save(pending.resent(hashToken(rawToken), now.plus(VERIFICATION_TOKEN_TTL), now));
			emailService.sendVerificationEmail(pending.email(), pending.name(), rawToken);
		});
	}

	@Override
	@Transactional
	public void logout(String token, String refreshToken) {
		if (StringUtils.hasText(token)) {
			authSessionRepository.revoke(token.trim(), clock.instant());
		}
		// An expired access cookie is gone from the browser while the refresh cookie still is:
		// without this the session could still be refreshed after "logging out".
		if (StringUtils.hasText(refreshToken)) {
			authSessionRepository.revokeByRefreshToken(refreshToken.trim(), clock.instant());
		}
	}

	@Override
	@Transactional(readOnly = true)
	public AuthUserView getAuthenticatedUser(String token) {
		String userId = authSessionRepository.findActiveUserId(token, clock.instant())
			.orElseThrow(() -> new ApiException(401, "UNAUTHENTICATED", "Sessao invalida ou expirada."));
		UserProfile profile = userProfileRepository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		return AuthUserView.from(profile, hasUsablePassword(userId));
	}

	/**
	 * A conta tem senha que a pessoa conhece?
	 *
	 * Quem entrou por login social nasce com um hash aleatorio (`password_usable = false`) — a tela
	 * usa isto para pedir "criar senha" em vez de "confirmar a senha atual".
	 */
	private boolean hasUsablePassword(String userId) {
		return credentialRepository.findByUserId(userId).map(StoredCredential::passwordUsable).orElse(false);
	}

	@Override
	@Transactional
	public void requestReset(String email) {
		if (!StringUtils.hasText(email) || !email.contains("@")) {
			throw new ApiException(400, "INVALID_EMAIL", "Email invalido.");
		}
		String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
		userProfileRepository.findByEmail(normalizedEmail).ifPresent(profile -> {
			// Per-account cap on top of the per-IP one: requests spread over many IPs cannot flood
			// one inbox. Silent, so the response stays neutral.
			if (!rateLimiter.tryAcquire(com.socialcoisa.rede.shared.security.IpRateLimiter.PASSWORD,
					"account:" + profile.id())) {
				LOG.warn("auth_reset_throttled userId={}", profile.id());
				return;
			}
			String rawToken = newResetToken();
			Instant now = clock.instant();
			// So o hash vai para o banco; o token em claro existe apenas no e-mail.
			passwordResetTokenRepository.deleteByUserId(profile.id());
			passwordResetTokenRepository.save(
				PasswordResetToken.create(hashToken(rawToken), profile.id(), now.plus(RESET_TOKEN_TTL), now));
			emailService.sendPasswordResetEmail(normalizedEmail, profile.name(), rawToken);
		});
	}

	@Override
	@Transactional
	public java.util.Optional<AuthResponse> reset(String token, String newPassword) {
		if (!StringUtils.hasText(token)) {
			throw new ApiException(400, "INVALID_TOKEN", "Token obrigatorio.");
		}
		validatePasswordStrength(newPassword);
		String tokenHash = hashToken(token.trim());
		PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(tokenHash)
			.orElseThrow(() -> new ApiException(404, "INVALID_TOKEN", "Token invalido ou nao encontrado."));
		if (resetToken.isUsed()) {
			throw new ApiException(409, "TOKEN_ALREADY_USED", "Token ja utilizado.");
		}
		if (resetToken.isExpired(clock.instant())) {
			throw new ApiException(410, "TOKEN_EXPIRED", "Token de reset expirado.");
		}
		StoredCredential credential = credentialRepository.findByUserId(resetToken.userId())
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		boolean wasExpired = credential.resetNeeded();
		credentialRepository.save(credential.userId(), credential.email(), passwordHasher.hash(newPassword));
		passwordResetTokenRepository.markUsed(tokenHash);
		authSessionRepository.revokeAllByUser(resetToken.userId(), clock.instant());
		if (wasExpired) {
			UserProfile profile = userProfileRepository.findById(resetToken.userId())
				.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
			return java.util.Optional.of(createResponse(profile, null));
		}
		return java.util.Optional.empty();
	}

	@Override
	@Transactional
	public void changeEmail(String sessionToken, String newEmail, String password) {
		String userId = requireUserId(sessionToken);
		if (!StringUtils.hasText(newEmail) || !newEmail.contains("@")) {
			throw new ApiException(400, "INVALID_EMAIL", "Email invalido.");
		}
		String normalized = newEmail.trim().toLowerCase(Locale.ROOT);
		StoredCredential credential = credentialRepository.findByUserId(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		if (!StringUtils.hasText(password) || !passwordHasher.matches(password, credential.passwordHash())) {
			throw new ApiException(401, "INVALID_CREDENTIALS", "Senha incorreta.");
		}
		if (normalized.equals(credential.email())) {
			return;
		}
		if (userProfileRepository.existsByEmail(normalized) || credentialRepository.existsByEmail(normalized)) {
			throw new ApiException(409, "EMAIL_ALREADY_REGISTERED", "Email ja esta em uso.");
		}
		UserProfile profile = userProfileRepository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		// NÃO aplica o endereço ainda: só depois do clique no link enviado para ele (E-02/E-11).
		String rawToken = newVerificationToken();
		Instant now = clock.instant();
		jdbc.update("delete from pending_email_changes where user_id = ?", userId);
		jdbc.update("""
			insert into pending_email_changes (id, user_id, new_email, token_hash, expires_at, created_at)
			values (?, ?, ?, ?, ?, ?)
			""", "emailchg_" + UUID.randomUUID().toString().replace("-", ""), userId, normalized,
			hashToken(rawToken), java.sql.Timestamp.from(now.plus(VERIFICATION_TOKEN_TTL)),
			java.sql.Timestamp.from(now));
		emailService.sendEmailChangeConfirmation(normalized, profile.name(), rawToken);
	}

	@Override
	@Transactional
	public void confirmEmailChange(String rawToken) {
		if (!StringUtils.hasText(rawToken)) {
			throw new ApiException(400, "INVALID_TOKEN", "Token obrigatorio.");
		}
		String tokenHash = hashToken(rawToken.trim());
		var row = jdbc.query("""
			select user_id, new_email, expires_at from pending_email_changes where token_hash = ?
			""", (rs, n) -> new Object[] { rs.getString(1), rs.getString(2),
				rs.getTimestamp(3).toInstant() }, tokenHash).stream().findFirst()
			.orElseThrow(() -> new ApiException(404, "INVALID_TOKEN", "Link invalido ou ja usado."));
		String userId = (String) row[0];
		String newEmail = (String) row[1];
		Instant expiresAt = (Instant) row[2];
		Instant now = clock.instant();
		if (expiresAt.isBefore(now)) {
			jdbc.update("delete from pending_email_changes where user_id = ?", userId);
			throw new ApiException(410, "TOKEN_EXPIRED", "Link expirado. Peça a troca novamente.");
		}
		UserProfile profile = userProfileRepository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		StoredCredential credential = credentialRepository.findByUserId(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		if (userProfileRepository.existsByEmail(newEmail) || credentialRepository.existsByEmail(newEmail)) {
			throw new ApiException(409, "EMAIL_ALREADY_REGISTERED", "Email ja esta em uso.");
		}
		String oldEmail = credential.email();
		profile.changeEmail(newEmail, now);
		profile.markEmailVerified(now);
		userProfileRepository.save(profile);
		credentialRepository.save(userId, newEmail, credential.passwordHash());
		// Sessões antigas caem: o endereço de recuperação mudou.
		authSessionRepository.revokeAllByUser(userId, now);
		jdbc.update("delete from pending_email_changes where user_id = ?", userId);
		emailService.sendHtmlEmail(oldEmail, "Seu e-mail foi alterado — Énóis",
			"<p>O e-mail da sua conta no Énóis foi alterado para <b>"
				+ EmailService.escapeHtml(newEmail)
				+ "</b>. Se não foi você, fale com o suporte imediatamente.</p>");
	}

	@Override
	@Transactional
	public void changePassword(String sessionToken, String currentPassword, String newPassword) {
		String userId = requireUserId(sessionToken);
		validatePasswordStrength(newPassword);
		StoredCredential credential = credentialRepository.findByUserId(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		// Conta criada por login social nasce com um hash aleatorio que ninguem conhece: aqui a
		// pessoa CRIA a primeira senha, entao nao ha o que confirmar. Exigir a senha atual nesse
		// caso trancava a conta para fora — o segundo fator manda "defina uma senha" e a troca
		// pedia uma senha que nunca existiu.
		if (credential.passwordUsable()
				&& (!StringUtils.hasText(currentPassword)
						|| !passwordHasher.matches(currentPassword, credential.passwordHash()))) {
			throw new ApiException(401, "INVALID_CREDENTIALS", "Senha atual incorreta.");
		}
		credentialRepository.save(userId, credential.email(), passwordHasher.hash(newPassword));
		// Encerra todas as sessoes: e preciso entrar de novo com a nova senha.
		authSessionRepository.revokeAllByUser(userId, clock.instant());
	}

	@Override
	@Transactional
	public void deleteAccount(String sessionToken, String password) {
		String userId = requireUserId(sessionToken);
		StoredCredential credential = credentialRepository.findByUserId(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		if (!StringUtils.hasText(password) || !passwordHasher.matches(password, credential.passwordHash())) {
			throw new ApiException(401, "INVALID_CREDENTIALS", "Senha incorreta.");
		}
		UserProfile profile = userProfileRepository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		Instant now = clock.instant();
		authSessionRepository.revokeAllByUser(userId, now);
		credentialRepository.deleteByUserId(userId);
		externalIdentityRepository.deleteByUserId(userId);
		String suffix = userId.startsWith("user_") ? userId.substring("user_".length()) : userId;
		profile.anonymize("removed+" + suffix + "@enois.invalid", "removed-" + suffix, now);
		userProfileRepository.save(profile);
	}

	/**
	 * Cria um novo token de confirmacao (invalidando o anterior) e envia o link.
	 *
	 * @return false quando o envio do e-mail falhou — a conta continua pendente e a tela oferece reenviar
	 */
	private boolean issueVerificationToken(UserProfile profile, Instant now) {
		String rawToken = newVerificationToken();
		// Um token valido por vez: reenviar invalida o link anterior (marcado como usado).
		emailVerificationTokenRepository.markAllUsedByUserId(profile.id(), now);
		emailVerificationTokenRepository.save(EmailVerificationToken.create(hashToken(rawToken), profile.id(),
			now.plus(VERIFICATION_TOKEN_TTL), now));
		return emailService.sendVerificationEmail(profile.email(), profile.name(), rawToken);
	}

	/** Chamado pelo login social: o provedor ja confirmou o e-mail, entao a conta nasce verificada. */
	@Transactional
	public void markEmailVerifiedForProvider(String userId, Instant now) {
		userProfileRepository.findById(userId).ifPresent(profile -> {
			if (!profile.emailVerified()) {
				profile.markEmailVerified(now);
				userProfileRepository.save(profile);
			}
		});
	}

	/**
	 * Exige identidade verificada: e-mail confirmado OU segundo fator ativo. A mesma regra sustenta a
	 * credibilidade (sem nenhum dos dois, a reputacao e zerada).
	 */
	private void requireVerifiedIdentity(UserProfile profile) {
		if (!profile.identityVerified()) {
			throw new ApiException(403, "EMAIL_NOT_VERIFIED",
				"Confirme seu e-mail ou ative a verificacao em duas etapas para entrar. "
					+ "Enviamos o link de confirmacao para " + normalizedEmail(profile) + ".");
		}
	}

	private String normalizedEmail(UserProfile profile) {
		return profile.email() == null ? "" : profile.email().trim().toLowerCase(Locale.ROOT);
	}

	private String requireUserId(String sessionToken) {
		if (!StringUtils.hasText(sessionToken)) {
			throw new ApiException(401, "UNAUTHENTICATED", "Sessao invalida ou expirada.");
		}
		return authSessionRepository.findActiveUserId(sessionToken.trim(), clock.instant())
			.orElseThrow(() -> new ApiException(401, "UNAUTHENTICATED", "Sessao invalida ou expirada."));
	}

	private AuthResponse createResponse(UserProfile profile, String ip) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(accessTokenTtl);
		Instant refreshExpiresAt = now.plus(refreshTokenTtl);
		String token = newToken();
		String refreshToken = newToken();
		authSessionRepository.save(token, refreshToken, profile.id(), now, expiresAt, refreshExpiresAt,
			ip == null || ip.isBlank() ? null : ip.trim());
		return new AuthResponse(token, refreshToken, expiresAt, refreshExpiresAt,
			AuthUserView.from(profile, hasUsablePassword(profile.id())));
	}

	private AuthResponse refreshSession(String currentRefreshToken) {
		Instant now = clock.instant();
		String userId = authSessionRepository.findActiveUserIdByRefreshToken(currentRefreshToken, now)
			.orElseThrow(() -> new ApiException(401, "INVALID_REFRESH_TOKEN", "Refresh token invalido ou expirado."));
		// Revogação atômica: dois refreshes concorrentes do mesmo token não podem gerar duas sessões.
		if (!authSessionRepository.revokeByRefreshToken(currentRefreshToken, now)) {
			throw new ApiException(401, "INVALID_REFRESH_TOKEN", "Refresh token invalido ou expirado.");
		}
		UserProfile profile = userProfileRepository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		return createResponse(profile, null);
	}

	@Override
	public AuthResponse refresh(String refreshToken) {
		if (!StringUtils.hasText(refreshToken)) {
			throw new ApiException(400, "INVALID_REFRESH_TOKEN", "Refresh token obrigatorio.");
		}
		return refreshSession(refreshToken.trim());
	}

	/** Hash descartável para igualar o custo do login quando o e-mail não existe. */
	private String dummyPasswordHash() {
		String hash = dummyPasswordHash;
		if (hash == null) {
			synchronized (this) {
				if (dummyPasswordHash == null) {
					dummyPasswordHash = passwordHasher.hash(newToken());
				}
				hash = dummyPasswordHash;
			}
		}
		return hash;
	}

	private String newToken() {
		byte[] bytes = new byte[32];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String newResetToken() {
		byte[] bytes = new byte[48];
		secureRandom.nextBytes(bytes);
		return "reset_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String newVerificationToken() {
		byte[] bytes = new byte[48];
		secureRandom.nextBytes(bytes);
		return "verify_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * Canonical mailbox: no "+tag" and, for Gmail, no dots in the local part. Two accounts must not be
	 * created for the same inbox.
	 */
	static String canonicalEmail(String email) {
		int at = email.lastIndexOf('@');
		if (at <= 0) {
			return email;
		}
		String local = email.substring(0, at);
		String domain = email.substring(at);
		int plus = local.indexOf('+');
		if (plus > 0) {
			local = local.substring(0, plus);
		}
		if (domain.equals("@gmail.com") || domain.equals("@googlemail.com")) {
			local = local.replace(".", "");
		}
		return local + domain;
	}

	private String uniqueUsername(String name, String email) {
		String base = UsernameSlug.from(StringUtils.hasText(name) ? name : email.substring(0, email.indexOf('@')));
		String candidate = base;
		int suffix = 1;
		while (userProfileRepository.existsByUsername(candidate)
				|| com.socialcoisa.rede.shared.web.AdminGuard.isReservedUsername(candidate)) {
			candidate = base + "-" + suffix++;
		}
		return candidate;
	}


	/** Hash do token de reset: so o hash e persistido, para nao guardar credencial em claro. */
	private String hashToken(String rawToken) {
		try {
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
				.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		}
		catch (java.security.NoSuchAlgorithmException exception) {
			throw new ApiException(500, "HASH_FAILURE", "Nao foi possivel processar o token.");
		}
	}

	/** Max length bounds the hashing cost; the blocklist is bundled, so no network call is made. */
	static void validatePasswordStrength(String password) {
		if (!StringUtils.hasText(password) || password.length() < 8) {
			throw new ApiException(400, "WEAK_PASSWORD", "Senha deve ter pelo menos 8 caracteres.");
		}
		if (password.length() > MAX_PASSWORD_LENGTH) {
			throw new ApiException(400, "WEAK_PASSWORD", "Senha deve ter no maximo 128 caracteres.");
		}
		if (COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
			throw new ApiException(400, "WEAK_PASSWORD", "Escolha uma senha menos previsivel.");
		}
	}

	private static java.util.Set<String> loadCommonPasswords() {
		try (var in = AuthService.class.getResourceAsStream("/security/common-passwords.txt")) {
			if (in == null) {
				throw new IllegalStateException("security/common-passwords.txt missing from the classpath");
			}
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
				.map(String::trim)
				.filter(line -> !line.isEmpty() && !line.startsWith("#"))
				.map(line -> line.toLowerCase(Locale.ROOT))
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		}
		catch (java.io.IOException exception) {
			throw new java.io.UncheckedIOException(exception);
		}
	}

	private void validateSignup(SignupCommand command) {
		if (!StringUtils.hasText(command.name()) || !StringUtils.hasText(command.role())
				|| !StringUtils.hasText(command.email()) || !StringUtils.hasText(command.password())) {
			throw new ApiException(400, "INVALID_SIGNUP", "Nome, cargo, email e senha sao obrigatorios.");
		}
		validatePasswordStrength(command.password());
		if (!command.email().contains("@")) {
			throw new ApiException(400, "INVALID_EMAIL", "Email invalido.");
		}
		if (!Boolean.TRUE.equals(command.acceptedTerms())) {
			throw new ApiException(400, "TERMS_NOT_ACCEPTED",
				"E necessario aceitar os termos de uso e confirmar que voce tem 18 anos ou mais.");
		}
	}

	private void validateLogin(LoginCommand command) {
		if (!StringUtils.hasText(command.email()) || !StringUtils.hasText(command.password())) {
			throw new ApiException(400, "INVALID_LOGIN", "Email e senha sao obrigatorios.");
		}
	}
}