package com.socialcoisa.rede.identity.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
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
import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.identity.application.port.out.CredentialRepository;
import com.socialcoisa.rede.identity.application.port.out.PasswordResetTokenRepository;
import com.socialcoisa.rede.identity.application.port.out.StoredCredential;
import com.socialcoisa.rede.identity.application.port.out.UserExternalIdentityRepository;
import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.identity.domain.PasswordResetToken;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class AuthService implements SignupUseCase, LoginUseCase, LogoutUseCase, GetAuthenticatedUserUseCase,
		RequestPasswordResetUseCase, ResetPasswordUseCase, RefreshSessionUseCase, ChangeEmailUseCase,
		ChangePasswordUseCase, DeleteAccountUseCase {

	private static final Duration RESET_TOKEN_TTL = Duration.ofHours(24);

	private final Duration accessTokenTtl;
	private final Duration refreshTokenTtl;
	private final UserProfileRepository userProfileRepository;
	private final CredentialRepository credentialRepository;
	private final AuthSessionRepository authSessionRepository;
	private final PasswordResetTokenRepository passwordResetTokenRepository;
	private final UserExternalIdentityRepository externalIdentityRepository;
	private final PasswordHasher passwordHasher;
	private final EmailService emailService;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();

	public AuthService(@Value("${rede.auth.access-token-ttl-minutes:60}") int accessTokenTtlMinutes,
                       @Value("${rede.auth.refresh-token-ttl-days:30}") int refreshTokenTtlDays,
                       UserProfileRepository userProfileRepository, CredentialRepository credentialRepository,
                       AuthSessionRepository authSessionRepository, PasswordResetTokenRepository passwordResetTokenRepository,
                       UserExternalIdentityRepository externalIdentityRepository,
                       PasswordHasher passwordHasher, EmailService emailService, Clock clock) {
		this.accessTokenTtl = Duration.ofMinutes(accessTokenTtlMinutes);
		this.refreshTokenTtl = Duration.ofDays(refreshTokenTtlDays);
		this.userProfileRepository = userProfileRepository;
		this.credentialRepository = credentialRepository;
		this.authSessionRepository = authSessionRepository;
		this.passwordResetTokenRepository = passwordResetTokenRepository;
		this.externalIdentityRepository = externalIdentityRepository;
		this.passwordHasher = passwordHasher;
        this.emailService = emailService;
        this.clock = clock;
	}

	@Override
	@Transactional
	public AuthResponse signup(SignupCommand command) {
		validateSignup(command);
		String email = command.email().trim().toLowerCase(Locale.ROOT);
		if (userProfileRepository.existsByEmail(email) || credentialRepository.existsByEmail(email)) {
			throw new ApiException(409, "EMAIL_ALREADY_REGISTERED", "Email ja cadastrado.");
		}
		Instant now = clock.instant();
		String userId = "user_" + UUID.randomUUID().toString().replace("-", "");
		String username = uniqueUsername(command.name(), email);
		// Nao existe papel no schema; o headline deve ficar vazio ate o usuario preencher.
		UserProfile profile = UserProfile.create(userId, username, email, command.name().trim(), null, now);
		UserProfile saved = userProfileRepository.save(profile);
		credentialRepository.save(saved.id(), email, passwordHasher.hash(command.password()));
		return createResponse(saved);
	}

	@Override
	@Transactional
	public AuthResponse login(LoginCommand command) {
		validateLogin(command);
		String email = command.email().trim().toLowerCase(Locale.ROOT);
		StoredCredential credential = credentialRepository.findByEmail(email)
			.orElseThrow(() -> new ApiException(401, "INVALID_CREDENTIALS", "Email ou senha invalidos."));
		if (!passwordHasher.matches(command.password(), credential.passwordHash())) {
			throw new ApiException(401, "INVALID_CREDENTIALS", "Email ou senha invalidos.");
		}
		UserProfile profile = userProfileRepository.findById(credential.userId())
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		return createResponse(profile);
	}

	@Override
	@Transactional
	public void logout(String token) {
		if (StringUtils.hasText(token)) {
			authSessionRepository.revoke(token.trim(), clock.instant());
		}
	}

	@Override
	@Transactional(readOnly = true)
	public AuthUserView getAuthenticatedUser(String token) {
		String userId = authSessionRepository.findActiveUserId(token, clock.instant())
			.orElseThrow(() -> new ApiException(401, "UNAUTHENTICATED", "Sessao invalida ou expirada."));
		UserProfile profile = userProfileRepository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		return AuthUserView.from(profile);
	}

	@Override
	@Transactional
	public void requestReset(String email) {
		if (!StringUtils.hasText(email) || !email.contains("@")) {
			throw new ApiException(400, "INVALID_EMAIL", "Email invalido.");
		}
		String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
		userProfileRepository.findByEmail(normalizedEmail).ifPresent(profile -> {
			String token = newResetToken();
			Instant now = clock.instant();
			passwordResetTokenRepository.save(PasswordResetToken.create(token, profile.id(), now.plus(RESET_TOKEN_TTL), now));
			emailService.sendPasswordResetEmail(normalizedEmail, profile.name(), token);
		});
	}

	@Override
	@Transactional
	public void reset(String token, String newPassword) {
		if (!StringUtils.hasText(token)) {
			throw new ApiException(400, "INVALID_TOKEN", "Token obrigatorio.");
		}
		if (!StringUtils.hasText(newPassword) || newPassword.length() < 8) {
			throw new ApiException(400, "WEAK_PASSWORD", "Senha deve ter pelo menos 8 caracteres.");
		}
		PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(token.trim())
			.orElseThrow(() -> new ApiException(404, "INVALID_TOKEN", "Token invalido ou nao encontrado."));
		if (resetToken.isExpired(clock.instant())) {
			throw new ApiException(410, "TOKEN_EXPIRED", "Token de reset expirado.");
		}
		if (resetToken.isUsed()) {
			throw new ApiException(409, "TOKEN_ALREADY_USED", "Token ja utilizado.");
		}
		StoredCredential credential = credentialRepository.findByUserId(resetToken.userId())
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		credentialRepository.save(credential.userId(), credential.email(), passwordHasher.hash(newPassword));
		passwordResetTokenRepository.markUsed(token.trim());
		authSessionRepository.revokeAllByUser(resetToken.userId(), clock.instant());
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
		profile.changeEmail(normalized, clock.instant());
		userProfileRepository.save(profile);
		credentialRepository.save(userId, normalized, credential.passwordHash());
	}

	@Override
	@Transactional
	public void changePassword(String sessionToken, String currentPassword, String newPassword) {
		String userId = requireUserId(sessionToken);
		if (!StringUtils.hasText(newPassword) || newPassword.length() < 8) {
			throw new ApiException(400, "WEAK_PASSWORD", "Senha deve ter pelo menos 8 caracteres.");
		}
		StoredCredential credential = credentialRepository.findByUserId(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		if (!StringUtils.hasText(currentPassword)
				|| !passwordHasher.matches(currentPassword, credential.passwordHash())) {
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

	private String requireUserId(String sessionToken) {
		if (!StringUtils.hasText(sessionToken)) {
			throw new ApiException(401, "UNAUTHENTICATED", "Sessao invalida ou expirada.");
		}
		return authSessionRepository.findActiveUserId(sessionToken.trim(), clock.instant())
			.orElseThrow(() -> new ApiException(401, "UNAUTHENTICATED", "Sessao invalida ou expirada."));
	}

	private AuthResponse createResponse(UserProfile profile) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(accessTokenTtl);
		Instant refreshExpiresAt = now.plus(refreshTokenTtl);
		String token = newToken();
		String refreshToken = newToken();
		authSessionRepository.save(token, refreshToken, profile.id(), now, expiresAt);
		return new AuthResponse(token, refreshToken, expiresAt, refreshExpiresAt, AuthUserView.from(profile));
	}

	private AuthResponse refreshSession(String currentRefreshToken) {
		Instant now = clock.instant();
		String userId = authSessionRepository.findActiveUserIdByRefreshToken(currentRefreshToken, now)
			.orElseThrow(() -> new ApiException(401, "INVALID_REFRESH_TOKEN", "Refresh token invalido ou expirado."));
		authSessionRepository.revokeByRefreshToken(currentRefreshToken, now);
		UserProfile profile = userProfileRepository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		return createResponse(profile);
	}

	@Override
	public AuthResponse refresh(String refreshToken) {
		if (!StringUtils.hasText(refreshToken)) {
			throw new ApiException(400, "INVALID_REFRESH_TOKEN", "Refresh token obrigatorio.");
		}
		return refreshSession(refreshToken.trim());
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

	private String uniqueUsername(String name, String email) {
		String base = slug(StringUtils.hasText(name) ? name : email.substring(0, email.indexOf('@')));
		String candidate = base;
		int suffix = 1;
		while (userProfileRepository.existsByUsername(candidate)) {
			candidate = base + "-" + suffix++;
		}
		return candidate;
	}

	private String slug(String value) {
		String slug = value.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		return StringUtils.hasText(slug) ? slug : "user";
	}

	private void validateSignup(SignupCommand command) {
		if (!StringUtils.hasText(command.name()) || !StringUtils.hasText(command.role())
				|| !StringUtils.hasText(command.email()) || !StringUtils.hasText(command.password())) {
			throw new ApiException(400, "INVALID_SIGNUP", "Nome, cargo, email e senha sao obrigatorios.");
		}
		if (command.password().length() < 8) {
			throw new ApiException(400, "WEAK_PASSWORD", "Senha deve ter pelo menos 8 caracteres.");
		}
		if (!command.email().contains("@")) {
			throw new ApiException(400, "INVALID_EMAIL", "Email invalido.");
		}
	}

	private void validateLogin(LoginCommand command) {
		if (!StringUtils.hasText(command.email()) || !StringUtils.hasText(command.password())) {
			throw new ApiException(400, "INVALID_LOGIN", "Email e senha sao obrigatorios.");
		}
	}
}
