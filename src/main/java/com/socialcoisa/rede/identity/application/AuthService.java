package com.socialcoisa.rede.identity.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.identity.application.port.in.GetAuthenticatedUserUseCase;
import com.socialcoisa.rede.identity.application.port.in.LoginUseCase;
import com.socialcoisa.rede.identity.application.port.in.LogoutUseCase;
import com.socialcoisa.rede.identity.application.port.in.SignupUseCase;
import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.identity.application.port.out.CredentialRepository;
import com.socialcoisa.rede.identity.application.port.out.StoredCredential;
import com.socialcoisa.rede.profile.application.UserProfileView;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class AuthService implements SignupUseCase, LoginUseCase, LogoutUseCase, GetAuthenticatedUserUseCase {

	private static final Duration SESSION_TTL = Duration.ofDays(7);

	private final UserProfileRepository userProfileRepository;
	private final CredentialRepository credentialRepository;
	private final AuthSessionRepository authSessionRepository;
	private final PasswordHasher passwordHasher;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();

	public AuthService(UserProfileRepository userProfileRepository, CredentialRepository credentialRepository,
			AuthSessionRepository authSessionRepository, PasswordHasher passwordHasher, Clock clock) {
		this.userProfileRepository = userProfileRepository;
		this.credentialRepository = credentialRepository;
		this.authSessionRepository = authSessionRepository;
		this.passwordHasher = passwordHasher;
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
		UserProfile profile = UserProfile.create(userId, username, email, command.name().trim(), command.role().trim(),
			now);
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
		return AuthUserView.from(UserProfileView.from(profile));
	}

	private AuthResponse createResponse(UserProfile profile) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(SESSION_TTL);
		String token = newToken();
		authSessionRepository.save(token, profile.id(), now, expiresAt);
		return new AuthResponse(token, expiresAt, AuthUserView.from(UserProfileView.from(profile)));
	}

	private String newToken() {
		byte[] bytes = new byte[32];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
