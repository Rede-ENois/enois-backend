package com.socialcoisa.rede.identity.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.identity.adapters.out.oauth.OAuthProperties;
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

	private final Duration accessTokenTtl;
	private final Duration refreshTokenTtl;

	private final List<TokenValidator> tokenValidators;
	private final UserExternalIdentityRepository externalIdentityRepository;
	private final UserProfileRepository userProfileRepository;
	private final CredentialRepository credentialRepository;
	private final AuthSessionRepository authSessionRepository;
	private final PasswordHasher passwordHasher;
	private final OAuthProperties oAuthProperties;
	private final Clock clock;

	public SocialLoginService(@Value("${rede.auth.access-token-ttl-minutes:60}") int accessTokenTtlMinutes,
			@Value("${rede.auth.refresh-token-ttl-days:30}") int refreshTokenTtlDays,
			List<TokenValidator> tokenValidators,
			UserExternalIdentityRepository externalIdentityRepository,
			UserProfileRepository userProfileRepository,
			CredentialRepository credentialRepository,
			AuthSessionRepository authSessionRepository,
			PasswordHasher passwordHasher,
			OAuthProperties oAuthProperties,
			Clock clock) {
		this.accessTokenTtl = Duration.ofMinutes(accessTokenTtlMinutes);
		this.refreshTokenTtl = Duration.ofDays(refreshTokenTtlDays);
		this.tokenValidators = tokenValidators;
		this.externalIdentityRepository = externalIdentityRepository;
		this.userProfileRepository = userProfileRepository;
		this.credentialRepository = credentialRepository;
		this.authSessionRepository = authSessionRepository;
		this.passwordHasher = passwordHasher;
		this.oAuthProperties = oAuthProperties;
		this.clock = clock;
	}

	@Override
	public OAuthStartResponse start(OAuthProvider provider) {
		String state = "state_" + UUID.randomUUID().toString().replace("-", "");
		String authorizationUrl = buildAuthorizationUrl(provider, state);
		return new OAuthStartResponse(provider, state, authorizationUrl);
	}

	private String buildAuthorizationUrl(OAuthProvider provider, String state) {
		return switch (provider) {
			case GOOGLE -> {
				var config = oAuthProperties.getGoogle();
				if (!StringUtils.hasText(config.getClientId())) {
					throw new ApiException(501, "OAUTH_NOT_CONFIGURED",
						"Login com Google nao configurado. Configure GOOGLE_CLIENT_ID.");
				}
				yield String.format(
					"https://accounts.google.com/o/oauth2/v2/auth?client_id=%s&redirect_uri=%s&response_type=code&scope=openid+email+profile&state=%s",
					urlEncode(config.getClientId()),
					urlEncode(config.getRedirectUri()),
					urlEncode(state)
				);
			}
			case APPLE -> {
				var config = oAuthProperties.getApple();
				if (!StringUtils.hasText(config.getClientId())) {
					throw new ApiException(501, "OAUTH_NOT_CONFIGURED",
						"Login com Apple nao configurado. Configure APPLE_CLIENT_ID.");
				}
				yield String.format(
					"https://appleid.apple.com/auth/authorize?client_id=%s&redirect_uri=%s&response_type=code&scope=name+email&response_mode=form_post&state=%s",
					urlEncode(config.getClientId()),
					urlEncode(config.getRedirectUri()),
					urlEncode(state)
				);
			}
		};
	}

	private String urlEncode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	@Override
	@Transactional
	public AuthResponse callback(OAuthProvider provider, String code, String state) {
		if (!StringUtils.hasText(code)) {
			throw new ApiException(400, "INVALID_OAUTH_CODE", "Codigo de autorizacao obrigatorio.");
		}
		if (!StringUtils.hasText(state)) {
			throw new ApiException(400, "INVALID_OAUTH_STATE", "State obrigatorio.");
		}
		TokenValidationResult validation = tokenValidators.stream()
			.map(v -> v.validate(provider, code))
			.filter(Optional::isPresent)
			.findFirst()
			.orElseThrow(() -> new ApiException(401, "INVALID_OAUTH_TOKEN",
				"Falha ao validar token do " + provider.name() + "."))
			.get();
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
		Instant expiresAt = now.plus(accessTokenTtl);
		Instant refreshExpiresAt = now.plus(refreshTokenTtl);
		byte[] bytes = new byte[32];
		new java.security.SecureRandom().nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		byte[] refreshBytes = new byte[32];
		new java.security.SecureRandom().nextBytes(refreshBytes);
		String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(refreshBytes);
		authSessionRepository.save(token, refreshToken, userId, now, expiresAt);
		UserProfile profile = userProfileRepository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		return new AuthResponse(token, refreshToken, expiresAt, refreshExpiresAt, AuthUserView.from(com.socialcoisa.rede.profile.application.UserProfileView.from(profile)));
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
