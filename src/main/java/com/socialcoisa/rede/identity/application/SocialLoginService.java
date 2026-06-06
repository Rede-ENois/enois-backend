package com.socialcoisa.rede.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.identity.application.port.in.OAuthStartResponse;
import com.socialcoisa.rede.identity.application.port.in.OAuthUseCase;
import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.identity.application.port.out.CredentialRepository;
import com.socialcoisa.rede.identity.application.port.out.TokenValidationResult;
import com.socialcoisa.rede.identity.application.port.out.TokenValidator;
import com.socialcoisa.rede.identity.application.port.out.UserExternalIdentityRepository;
import com.socialcoisa.rede.identity.domain.OAuthProvider;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class SocialLoginService implements OAuthUseCase {

	private static final Duration SESSION_TTL = Duration.ofDays(7);

	private final TokenValidator tokenValidator;
	private final UserExternalIdentityRepository externalIdentityRepository;
	private final UserProfileRepository userProfileRepository;
	private final CredentialRepository credentialRepository;
	private final AuthSessionRepository authSessionRepository;
	private final PasswordHasher passwordHasher;
	private final Clock clock;

	public SocialLoginService(TokenValidator tokenValidator,
			UserExternalIdentityRepository externalIdentityRepository,
			UserProfileRepository userProfileRepository,
			CredentialRepository credentialRepository,
			AuthSessionRepository authSessionRepository,
			PasswordHasher passwordHasher,
			Clock clock) {
		this.tokenValidator = tokenValidator;
		this.externalIdentityRepository = externalIdentityRepository;
		this.userProfileRepository = userProfileRepository;
		this.credentialRepository = credentialRepository;
		this.authSessionRepository = authSessionRepository;
		this.passwordHasher = passwordHasher;
		this.clock = clock;
	}

	@Override
	public OAuthStartResponse start(OAuthProvider provider) {
		String state = "state_" + UUID.randomUUID().toString().replace("-", "");
		String authorizationUrl = "/api/auth/oauth/" + provider.name().toLowerCase() + "/callback?state=" + state;
		return new OAuthStartResponse(provider, state, authorizationUrl);
	}

	@Override
	@Transactional
	public AuthResponse callback(OAuthProvider provider, String code, String state) {
		if (!StringUtils.hasText(code)) {
			throw new ApiException(400, "INVALID_OAUTH_CODE", "Codigo de autorizacao obrigatorio.");
		}
		TokenValidationResult validation = tokenValidator.validate(provider, code)
			.orElseThrow(() -> new ApiException(401, "INVALID_OAUTH_TOKEN",
				"Falha ao validar token do " + provider.name() + "."));
		String userId = findOrCreateUser(provider, validation);
		return createSession(userId);
	}

	private String findOrCreateUser(OAuthProvider provider, TokenValidationResult validation) {
		Optional<String> existingUserId = externalIdentityRepository
			.findUserIdByProviderAndExternalId(provider.name(), validation.externalUserId());
		if (existingUserId.isPresent()) {
			return existingUserId.get();
		}
		String email = validation.email();
		if (StringUtils.hasText(email) && userProfileRepository.existsByEmail(email.trim().toLowerCase(Locale.ROOT))) {
			String existingUser = userProfileRepository.findByEmail(email.trim().toLowerCase(Locale.ROOT))
				.map(UserProfile::id)
				.orElseThrow();
			externalIdentityRepository.save(existingUser, provider.name(), validation.externalUserId(), email);
			return existingUser;
		}
		Instant now = clock.instant();
		String userId = "user_" + UUID.randomUUID().toString().replace("-", "");
		String username = uniqueUsername(validation.name(), email);
		String safeName = StringUtils.hasText(validation.name()) ? validation.name().trim() : "User";
		UserProfile profile = UserProfile.create(userId, username, email, safeName, "", now);
		userProfileRepository.save(profile);
		String randomPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
		credentialRepository.save(userId, email, passwordHasher.hash(randomPassword));
		externalIdentityRepository.save(userId, provider.name(), validation.externalUserId(), email);
		return userId;
	}

	private AuthResponse createSession(String userId) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(SESSION_TTL);
		byte[] bytes = new byte[32];
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		authSessionRepository.save(token, userId, now, expiresAt);
		UserProfile profile = userProfileRepository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		return new AuthResponse(token, expiresAt, AuthUserView.from(com.socialcoisa.rede.profile.application.UserProfileView.from(profile)));
	}

	private String uniqueUsername(String name, String email) {
		String base = slug(StringUtils.hasText(name) ? name : (StringUtils.hasText(email) ? email.substring(0, email.indexOf('@')) : "user"));
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
}
