package com.socialcoisa.rede.identity.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.contributions.application.GitHubIdentityLinkedEvent;
import com.socialcoisa.rede.contributions.application.GitHubIdentityService;
import com.socialcoisa.rede.contributions.application.GitHubUserTokenService;
import com.socialcoisa.rede.identity.adapters.out.oauth.OAuthProperties;
import com.socialcoisa.rede.identity.application.port.in.LinkedProviderView;
import com.socialcoisa.rede.identity.application.port.in.OAuthStartResponse;
import com.socialcoisa.rede.identity.application.port.in.OAuthUseCase;
import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.identity.application.port.out.CredentialRepository;
import com.socialcoisa.rede.identity.application.port.out.OAuthAuthorizationRequestRepository;
import com.socialcoisa.rede.identity.application.port.out.StoredCredential;
import com.socialcoisa.rede.identity.application.port.out.TokenValidationResult;
import com.socialcoisa.rede.identity.application.port.out.TokenValidator;
import com.socialcoisa.rede.identity.application.port.out.UserExternalIdentityRepository;
import com.socialcoisa.rede.identity.domain.OAuthAuthorizationRequest;
import com.socialcoisa.rede.identity.domain.OAuthProvider;
import com.socialcoisa.rede.identity.domain.UsernameSlug;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Login e vinculacao por Google/Apple.
 *
 * Regras que valem a pena ter em mente:
 * - todo fluxo comeca em {@link #start}/{@link #startLink}, que registram `state` + `nonce` no
 *   servidor; o callback so aceita um state que existe, e de uso unico;
 * - o id_token e validado com assinatura, emissor, audience, validade e nonce (ver os validadores);
 * - NUNCA vinculamos uma identidade a uma conta existente so porque o e-mail declarado bate: e-mail
 *   nao e prova de posse. Se a conta ja existe, o dono precisa entrar e vincular de proposito.
 */
@Service
public class SocialLoginService implements OAuthUseCase {

	/** Tempo que o usuario tem para concluir a tela do provedor. */
	private static final Duration STATE_TTL = Duration.ofMinutes(15);

	private final Duration accessTokenTtl;
	private final Duration refreshTokenTtl;

	private final List<TokenValidator> tokenValidators;
	private final UserExternalIdentityRepository externalIdentityRepository;
	private final OAuthAuthorizationRequestRepository authorizationRequestRepository;
	private final UserProfileRepository userProfileRepository;
	private final CredentialRepository credentialRepository;
	private final AuthSessionRepository authSessionRepository;
	private final PasswordHasher passwordHasher;
	private final OAuthProperties oAuthProperties;
	private final com.socialcoisa.rede.linkedin.application.LinkedInIntegrationService linkedin;
	private final GitHubIdentityService githubIdentity;
	private final GitHubUserTokenService githubTokens;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	/** Cadastros abertos? Desligado, provedor so ENTRA em conta existente, nunca cria uma nova. */
	private final boolean signupEnabled;
	private final SecureRandom secureRandom = new SecureRandom();

	public SocialLoginService(@Value("${rede.auth.access-token-ttl-minutes:60}") int accessTokenTtlMinutes,
			@Value("${rede.auth.refresh-token-ttl-days:30}") int refreshTokenTtlDays,
			List<TokenValidator> tokenValidators,
			UserExternalIdentityRepository externalIdentityRepository,
			OAuthAuthorizationRequestRepository authorizationRequestRepository,
			UserProfileRepository userProfileRepository,
			CredentialRepository credentialRepository,
			AuthSessionRepository authSessionRepository,
			PasswordHasher passwordHasher,
			OAuthProperties oAuthProperties,
			com.socialcoisa.rede.linkedin.application.LinkedInIntegrationService linkedin,
			GitHubIdentityService githubIdentity, GitHubUserTokenService githubTokens,
			ApplicationEventPublisher events, Clock clock,
			@Value("${rede.auth.signup-enabled:true}") boolean signupEnabled) {
		this.accessTokenTtl = Duration.ofMinutes(accessTokenTtlMinutes);
		this.refreshTokenTtl = Duration.ofDays(refreshTokenTtlDays);
		this.tokenValidators = tokenValidators;
		this.externalIdentityRepository = externalIdentityRepository;
		this.authorizationRequestRepository = authorizationRequestRepository;
		this.userProfileRepository = userProfileRepository;
		this.credentialRepository = credentialRepository;
		this.authSessionRepository = authSessionRepository;
		this.passwordHasher = passwordHasher;
		this.oAuthProperties = oAuthProperties;
		this.linkedin = linkedin;
		this.githubIdentity = githubIdentity;
		this.githubTokens = githubTokens;
		this.events = events;
		this.clock = clock;
		this.signupEnabled = signupEnabled;
	}

	@Override
	@Transactional
	public OAuthStartResponse start(OAuthProvider provider) {
		return startRequest(provider, null);
	}

	@Override
	@Transactional
	public OAuthStartResponse startLink(String userId, OAuthProvider provider) {
		if (!StringUtils.hasText(userId)) {
			throw new ApiException(401, "UNAUTHENTICATED", "Sessao invalida ou expirada.");
		}
		return startRequest(provider, userId);
	}

	@Override
	@Transactional
	public OAuthStartResponse startLinkWithWrite(String userId, OAuthProvider provider) {
		if (!StringUtils.hasText(userId)) {
			throw new ApiException(401, "UNAUTHENTICATED", "Sessao invalida ou expirada.");
		}
		if (provider != OAuthProvider.GITHUB) {
			throw new ApiException(400, "WRITE_SCOPE_NOT_SUPPORTED",
				"Somente o GitHub tem vínculo com permissão de escrita.");
		}
		return startRequest(provider, userId, true);
	}

	private OAuthStartResponse startRequest(OAuthProvider provider, String linkUserId) {
		return startRequest(provider, linkUserId, false);
	}

	private OAuthStartResponse startRequest(OAuthProvider provider, String linkUserId, boolean writeScope) {
		// O GitHub só serve para VINCULAR: não criamos conta nova por ele (o e-mail público do
		// GitHub não é prova de posse e o fluxo de login não é o objetivo desta fase).
		if (provider == OAuthProvider.GITHUB && linkUserId == null) {
			throw new ApiException(403, "GITHUB_LINK_ONLY",
				"Entre com e-mail e senha e vincule o GitHub no seu perfil.");
		}
		String state = randomToken();
		String nonce = randomToken();
		Instant now = clock.instant();
		authorizationRequestRepository.save(OAuthAuthorizationRequest.create(state, provider, nonce, linkUserId,
			now.plus(STATE_TTL), now));
		// Faxina oportunista: o state nao precisa sobreviver no banco depois de vencer.
		authorizationRequestRepository.deleteExpired(now);
		return new OAuthStartResponse(provider, state,
			buildAuthorizationUrl(provider, state, nonce, linkUserId != null, writeScope));
	}

	private String buildAuthorizationUrl(OAuthProvider provider, String state, String nonce,
			boolean includePosting, boolean writeScope) {
		return switch (provider) {
			case GOOGLE -> {
				var config = oAuthProperties.getGoogle();
				if (!StringUtils.hasText(config.getClientId())) {
					throw new ApiException(501, "OAUTH_NOT_CONFIGURED",
						"Login com Google nao configurado. Configure GOOGLE_CLIENT_ID.");
				}
				yield "https://accounts.google.com/o/oauth2/v2/auth?client_id=" + urlEncode(config.getClientId())
					+ "&redirect_uri=" + urlEncode(config.getRedirectUri())
					+ "&response_type=code&scope=openid+email+profile"
					+ "&state=" + urlEncode(state) + "&nonce=" + urlEncode(nonce);
			}
			case APPLE -> {
				var config = oAuthProperties.getApple();
				if (!StringUtils.hasText(config.getClientId())) {
					throw new ApiException(501, "OAUTH_NOT_CONFIGURED",
						"Login com Apple nao configurado. Configure APPLE_CLIENT_ID.");
				}
				yield "https://appleid.apple.com/auth/authorize?client_id=" + urlEncode(config.getClientId())
					+ "&redirect_uri=" + urlEncode(config.getRedirectUri())
					+ "&response_type=code&scope=name+email&response_mode=form_post"
					+ "&state=" + urlEncode(state) + "&nonce=" + urlEncode(nonce);
			}
			case GITHUB -> {
				var config = oAuthProperties.getGithub();
				if (!StringUtils.hasText(config.getClientId())) {
					throw new ApiException(501, "OAUTH_NOT_CONFIGURED",
						"Vinculo com GitHub nao configurado. Configure GITHUB_CLIENT_ID.");
				}
				yield "https://github.com/login/oauth/authorize?client_id=" + urlEncode(config.getClientId())
					+ "&redirect_uri=" + urlEncode(config.getRedirectUri())
					+ "&response_type=code&scope=" + (writeScope ? "repo" : "read:user")
					+ "&state=" + urlEncode(state);
			}
			case LINKEDIN -> {
				var config = oAuthProperties.getLinkedin();
				if (!StringUtils.hasText(config.getClientId())) {
					throw new ApiException(501, "OAUTH_NOT_CONFIGURED",
						"Login com LinkedIn nao configurado. Configure LINKEDIN_CLIENT_ID.");
				}
				// OIDC do LinkedIn ("Sign In with LinkedIn using OpenID Connect"): os escopos
				// openid/profile/email sao de aprovacao automatica — importam nome, foto e e-mail.
				// No VINCULO pedimos tambem w_member_social (produto "Share on LinkedIn"), que
				// autoriza publicar em nome da pessoa; o login puro nao pede esse escopo para nao
				// assustar quem so quer entrar. Se o app ainda nao tem o produto, so o vinculo falha.
				yield "https://www.linkedin.com/oauth/v2/authorization?client_id=" + urlEncode(config.getClientId())
					+ "&redirect_uri=" + urlEncode(config.getRedirectUri())
					+ "&response_type=code&scope=openid+profile+email"
					+ (includePosting ? "+w_member_social" : "")
					+ "&state=" + urlEncode(state) + "&nonce=" + urlEncode(nonce);
			}
		};
	}

	private String urlEncode(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}

	@Override
	@Transactional
	public AuthResponse callback(OAuthProvider provider, String code, String state, String appleUserPayload) {
		if (!StringUtils.hasText(code)) {
			throw new ApiException(400, "INVALID_OAUTH_CODE", "Codigo de autorizacao obrigatorio.");
		}
		OAuthAuthorizationRequest request = consumeAuthorizationRequest(provider, state);
		TokenValidationResult validation = validate(provider, code, request.nonce());
		AuthResponse response = request.isLinkRequest()
			? attachIdentity(request.userId(), provider, validation)
			: login(provider, validation, appleUserPayload);
		rememberLinkedInAccess(provider, response, validation);
		return response;
	}

	@Override
	@Transactional
	public AuthResponse tokenLogin(OAuthProvider provider, String idToken, String state) {
		if (!StringUtils.hasText(idToken)) {
			throw new ApiException(400, "INVALID_OAUTH_TOKEN", "id_token obrigatorio.");
		}
		// Sem state nao ha como conferir o nonce: um id_token solto pode ter sido emitido para outro
		// pedido. Aceitamos o login por id_token apenas quando ele veio do nosso /start.
		OAuthAuthorizationRequest request = consumeAuthorizationRequest(provider, state);
		TokenValidationResult validation = validate(provider, idToken, request.nonce());
		AuthResponse response = request.isLinkRequest()
			? attachIdentity(request.userId(), provider, validation)
			: login(provider, validation, null);
		rememberLinkedInAccess(provider, response, validation);
		return response;
	}

	/**
	 * O token de acesso do LinkedIn só existe no fluxo por `code` e só é guardado quando o
	 * vínculo pediu `w_member_social`. Fica cifrado (ver LinkedInIntegrationService) e nunca
	 * viaja para o cliente.
	 */
	private void rememberGithub(String userId, TokenValidationResult validation) {
		githubIdentity.remember(userId, validation.externalUserId(), validation.name());
		// O token só é guardado quando o consentimento inclui `repo` (vínculo de moderação).
		githubTokens.remember(userId, validation.accessToken(), validation.scope());
		// Conectar o GitHub precisa verificar as contribuições na hora: sem isso o perfil fica sem
		// selo até a próxima varredura (que nasce desligada). O listener roda fora da transação.
		events.publishEvent(new GitHubIdentityLinkedEvent(userId, validation.externalUserId()));
	}

	private void rememberLinkedInAccess(OAuthProvider provider, AuthResponse response,
			TokenValidationResult validation) {
		if (provider == OAuthProvider.LINKEDIN && StringUtils.hasText(validation.accessToken())) {
			linkedin.remember(response.user().id(), validation.externalUserId(), validation.accessToken(),
				validation.accessTokenExpiresAt());
		}
	}

	/**
	 * Confere o state no servidor: precisa existir, ser do mesmo provedor e estar dentro da validade.
	 * O registro e apagado na mesma transacao (uso unico), o que tambem inviabiliza reenvio.
	 */
	private OAuthAuthorizationRequest consumeAuthorizationRequest(OAuthProvider provider, String state) {
		if (!StringUtils.hasText(state)) {
			throw new ApiException(400, "INVALID_OAUTH_STATE", "Requisicao de login invalida ou expirada.");
		}
		OAuthAuthorizationRequest request = authorizationRequestRepository.findByState(state.trim())
			.orElseThrow(() -> new ApiException(400, "INVALID_OAUTH_STATE",
				"Requisicao de login invalida ou expirada."));
		authorizationRequestRepository.deleteByState(request.state());
		if (request.provider() != provider) {
			throw new ApiException(400, "INVALID_OAUTH_STATE", "Requisicao de login invalida ou expirada.");
		}
		if (request.isExpired(clock.instant())) {
			throw new ApiException(410, "OAUTH_STATE_EXPIRED", "Esta tentativa de login expirou. Tente de novo.");
		}
		return request;
	}

	private TokenValidationResult validate(OAuthProvider provider, String credential, String expectedNonce) {
		return tokenValidators.stream()
			.map(validator -> validator.validate(provider, credential, expectedNonce))
			.filter(Optional::isPresent)
			.map(Optional::get)
			.findFirst()
			.orElseThrow(() -> new ApiException(401, "INVALID_OAUTH_TOKEN",
				"Nao foi possivel validar sua conta " + provider.name() + ". Tente novamente."));
	}

	/** Login/cadastro pelo provedor (nao e vinculacao). */
	private AuthResponse login(OAuthProvider provider, TokenValidationResult validation, String appleUserPayload) {
		String userId = externalIdentityRepository
			.findUserIdByProviderAndExternalId(provider.name(), validation.externalUserId())
			.orElseGet(() -> resolveOrCreateAccount(provider, validation, appleUserPayload));
		// O provedor confirma o e-mail e responde pela segunda etapa: a conta entra verificada e com o
		// segundo fator delegado (sem inventar um TOTP que nao existe).
		markVerifiedByProvider(userId);
		return createSession(userId);
	}

	/**
	 * Conta ja existente com este e-mail: vincula em vez de recusar — mas SO quando o provedor afirma
	 * que o e-mail e verificado. E-mail "declarado" (sem essa garantia) nunca vincula sozinho: seria
	 * entregar a conta a quem apenas digitou o endereco.
	 */
	private String resolveOrCreateAccount(OAuthProvider provider, TokenValidationResult validation,
			String appleUserPayload) {
		String email = normalizeEmail(validation.email());
		if (StringUtils.hasText(email)
				&& (userProfileRepository.existsByEmail(email) || credentialRepository.existsByEmail(email))) {
			if (!validation.emailVerified()) {
				throw new ApiException(409, "ACCOUNT_EXISTS_UNVERIFIED_EMAIL",
					"Ja existe uma conta com este e-mail e o " + provider.name()
						+ " nao confirmou que ele e seu. Entre com e-mail e senha e vincule depois.");
			}
			String existing = userProfileRepository.findByEmail(email).map(UserProfile::id)
				.orElseGet(() -> credentialRepository.findByEmail(email)
					.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."))
					.userId());
			externalIdentityRepository.save(existing, provider.name(), validation.externalUserId(), email);
			return existing;
		}
		return createAccountForProvider(provider, validation, appleUserPayload);
	}

	private String createAccountForProvider(OAuthProvider provider, TokenValidationResult validation,
			String appleUserPayload) {
		// Login de quem JA tem conta continua; o que fecha aqui e a criacao de conta nova pelo
		// provedor (mesmo portao do POST /auth/signup).
		if (!signupEnabled) {
			throw new ApiException(403, "SIGNUP_DISABLED",
				"Os cadastros estao fechados no momento. Se voce ja tem conta, entre com e-mail e senha.");
		}
		String email = normalizeEmail(validation.email());
		if (!StringUtils.hasText(email)) {
			throw new ApiException(409, "OAUTH_EMAIL_REQUIRED",
				"O provedor nao informou um e-mail. Entre com e-mail e senha e vincule a conta depois.");
		}
		// Chegamos aqui sem conta previa (o vinculo por e-mail verificado acontece antes).
		if (userProfileRepository.existsByEmail(email) || credentialRepository.existsByEmail(email)) {
			throw new ApiException(409, "ACCOUNT_EXISTS_LINK_REQUIRED",
				"Ja existe uma conta com este e-mail. Entre com e-mail e senha e vincule o "
					+ provider.name() + " no seu perfil.");
		}
		Instant now = clock.instant();
		String userId = "user_" + UUID.randomUUID().toString().replace("-", "");
		String name = StringUtils.hasText(validation.name()) ? validation.name().trim()
			: nameFromApplePayload(appleUserPayload);
		String username = uniqueUsername(StringUtils.hasText(name) ? name : email, email);
		UserProfile profile = UserProfile.create(userId, username, email,
			StringUtils.hasText(name) ? name : "Pessoa", null, now);
		// A conta nasce de um provedor que validou o e-mail; o aceite dos termos vem no primeiro acesso.
		userProfileRepository.save(profile);
		// Senha aleatoria que o usuario nao conhece: a marca `passwordUsable=false` impede que
		// desvincular o provedor deixe a conta inacessivel.
		credentialRepository.save(userId, email, passwordHasher.hash(randomToken()), false);
		externalIdentityRepository.save(userId, provider.name(), validation.externalUserId(), email);
		return userId;
	}

	private String nameFromApplePayload(String appleUserPayload) {
		if (!StringUtils.hasText(appleUserPayload)) {
			return "";
		}
		try {
			@SuppressWarnings("unchecked")
			var payload = new com.fasterxml.jackson.databind.ObjectMapper().readValue(appleUserPayload, java.util.Map.class);
			Object name = payload.get("name");
			if (name instanceof java.util.Map<?, ?> nameMap) {
				String firstName = stringValue(nameMap.get("firstName"));
				String lastName = stringValue(nameMap.get("lastName"));
				if (StringUtils.hasText(firstName)) {
					return StringUtils.hasText(lastName) ? firstName + " " + lastName : firstName;
				}
			}
		}
		catch (Exception ignored) {
			// Payload opcional (a Apple so manda nome na primeira autorizacao).
		}
		return "";
	}

	private String stringValue(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	/** Vincula o provedor a conta de quem ja esta logado e pediu a vinculacao. */
	private AuthResponse attachIdentity(String userId, OAuthProvider provider, TokenValidationResult validation) {
		Optional<String> owner = externalIdentityRepository
			.findUserIdByProviderAndExternalId(provider.name(), validation.externalUserId());
		if (owner.isPresent() && !owner.get().equals(userId)) {
			throw new ApiException(409, "OAUTH_IDENTITY_IN_USE",
				"Esta conta " + provider.name() + " ja esta vinculada a outro usuario.");
		}
		if (owner.isPresent()) {
			// Já vinculada a esta mesma conta: atualiza o login do GitHub (o ID não muda).
			if (provider == OAuthProvider.GITHUB) {
				rememberGithub(userId, validation);
			}
			return createSession(userId);
		}
		if (externalIdentityRepository.findByUserId(userId).stream()
				.anyMatch(identity -> identity.provider().equals(provider.name()))) {
			throw new ApiException(409, "OAUTH_ALREADY_LINKED",
				"Voce ja tem uma conta " + provider.name() + " vinculada.");
		}
		externalIdentityRepository.save(userId, provider.name(), validation.externalUserId(),
			normalizeEmail(validation.email()));
		if (provider == OAuthProvider.GITHUB) {
			// GitHub é vínculo de contribuição, não prova de identidade civil nem segundo fator.
			rememberGithub(userId, validation);
		}
		else {
			markVerifiedByProvider(userId);
		}
		return createSession(userId);
	}

	@Override
	@Transactional
	public void unlink(String userId, OAuthProvider provider) {
		List<UserExternalIdentityRepository.LinkedIdentity> linked = externalIdentityRepository.findByUserId(userId);
		boolean hasProvider = linked.stream().anyMatch(identity -> identity.provider().equals(provider.name()));
		if (!hasProvider) {
			throw new ApiException(404, "OAUTH_NOT_LINKED",
				"Sua conta nao esta vinculada ao " + provider.name() + ".");
		}
		// Protecao contra perda de acesso: precisa sobrar outro jeito de entrar.
		boolean hasAnotherProvider = linked.stream().anyMatch(identity -> !identity.provider().equals(provider.name()));
		boolean hasUsablePassword = credentialRepository.findByUserId(userId)
			.map(StoredCredential::passwordUsable)
			.orElse(false);
		if (!hasAnotherProvider && !hasUsablePassword) {
			throw new ApiException(409, "LAST_LOGIN_METHOD",
				"Este e o unico jeito de entrar na sua conta. Defina uma senha antes de desvincular.");
		}
		externalIdentityRepository.deleteByUserIdAndProvider(userId, provider.name());
		if (provider == OAuthProvider.LINKEDIN) {
			// Sem o vinculo nao ha publicacao: apaga o token de "Share on LinkedIn" junto.
			linkedin.remove(userId);
		}
		if (provider == OAuthProvider.GITHUB) {
			githubIdentity.unlink(userId);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public List<LinkedProviderView> linkedProviders(String userId) {
		boolean hasUsablePassword = credentialRepository.findByUserId(userId)
			.map(StoredCredential::passwordUsable)
			.orElse(false);
		return externalIdentityRepository.findByUserId(userId).stream()
			.map(identity -> new LinkedProviderView(identity.provider(), identity.email(),
				identity.createdAt() != null ? identity.createdAt().toString() : null, hasUsablePassword))
			.toList();
	}

	/**
	 * Marca a conta como verificada pelo provedor: e-mail confirmado e segundo fator delegado. Quem
	 * entra por Google/Apple nao depende do nosso link de confirmacao nem precisa configurar 2FA.
	 */
	private void markVerifiedByProvider(String userId) {
		userProfileRepository.findById(userId).ifPresent(profile -> {
			Instant now = clock.instant();
			boolean changed = false;
			if (!profile.emailVerified()) {
				profile.markEmailVerified(now);
				changed = true;
			}
			if (!profile.federatedSecondFactor()) {
				profile.enableFederatedSecondFactor(now);
				changed = true;
			}
			if (changed) {
				userProfileRepository.save(profile);
			}
		});
	}

	private String normalizeEmail(String email) {
		return StringUtils.hasText(email) ? email.trim().toLowerCase(Locale.ROOT) : "";
	}

	private AuthResponse createSession(String userId) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(accessTokenTtl);
		Instant refreshExpiresAt = now.plus(refreshTokenTtl);
		String token = randomToken();
		String refreshToken = randomToken();
		authSessionRepository.save(token, refreshToken, userId, now, expiresAt, refreshExpiresAt, null);
		UserProfile profile = userProfileRepository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		// Quem entrou pelo provedor pode ja ter criado uma senha depois; a tela pergunta por ela
		// quando existe, e pede para criar quando nao.
		boolean hasPassword = credentialRepository.findByUserId(userId)
			.map(StoredCredential::passwordUsable)
			.orElse(false);
		return new AuthResponse(token, refreshToken, expiresAt, refreshExpiresAt,
			AuthUserView.from(profile, hasPassword));
	}

	private String randomToken() {
		byte[] bytes = new byte[32];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String uniqueUsername(String name, String email) {
		String base = UsernameSlug.from(StringUtils.hasText(name) ? name
			: (StringUtils.hasText(email) ? email.substring(0, email.indexOf('@')) : "user"));
		String candidate = base;
		int suffix = 1;
		while (userProfileRepository.existsByUsername(candidate)
				|| com.socialcoisa.rede.shared.web.AdminGuard.isReservedUsername(candidate)) {
			candidate = base + "-" + suffix++;
		}
		return candidate;
	}
}
