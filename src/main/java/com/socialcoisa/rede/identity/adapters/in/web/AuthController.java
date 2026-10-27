package com.socialcoisa.rede.identity.adapters.in.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.identity.adapters.out.oauth.OAuthProperties;
import com.socialcoisa.rede.identity.application.AuthResponse;
import com.socialcoisa.rede.identity.application.LoginCommand;
import com.socialcoisa.rede.identity.application.SignupChallengeService;
import com.socialcoisa.rede.identity.application.SignupCommand;
import com.socialcoisa.rede.identity.application.port.in.ChangeEmailUseCase;
import com.socialcoisa.rede.identity.application.port.in.ChangePasswordUseCase;
import com.socialcoisa.rede.identity.application.port.in.DeleteAccountUseCase;
import com.socialcoisa.rede.identity.application.port.in.GetAuthenticatedUserUseCase;
import com.socialcoisa.rede.identity.application.port.in.LoginUseCase;
import com.socialcoisa.rede.identity.application.port.in.LogoutUseCase;
import com.socialcoisa.rede.identity.application.port.in.OAuthStartResponse;
import com.socialcoisa.rede.identity.application.port.in.OAuthUseCase;
import com.socialcoisa.rede.identity.application.port.in.RefreshSessionUseCase;
import com.socialcoisa.rede.identity.application.port.in.RequestPasswordResetUseCase;
import com.socialcoisa.rede.identity.application.port.in.ResetPasswordUseCase;
import com.socialcoisa.rede.identity.application.LoginResult;
import com.socialcoisa.rede.identity.application.port.in.SignupUseCase;
import com.socialcoisa.rede.identity.application.port.in.TwoFactorLoginUseCase;
import com.socialcoisa.rede.identity.application.port.in.TwoFactorUseCase;
import com.socialcoisa.rede.identity.application.port.in.VerifyEmailUseCase;
import com.socialcoisa.rede.identity.domain.OAuthProvider;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.StatusResponse;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

@RestController
@RequestMapping("/auth")
class AuthController {

	private static final org.slf4j.Logger AUDIT = org.slf4j.LoggerFactory.getLogger(AuthController.class);

	private final SignupUseCase signupUseCase;
	private final VerifyEmailUseCase verifyEmailUseCase;
	private final TwoFactorLoginUseCase twoFactorLoginUseCase;
	private final TwoFactorUseCase twoFactorUseCase;
	private final LoginUseCase loginUseCase;
	private final LogoutUseCase logoutUseCase;
	private final GetAuthenticatedUserUseCase getAuthenticatedUserUseCase;
	private final OAuthUseCase oAuthUseCase;
	private final RefreshSessionUseCase refreshSessionUseCase;
	private final RequestPasswordResetUseCase requestPasswordResetUseCase;
	private final ResetPasswordUseCase resetPasswordUseCase;
	private final ChangeEmailUseCase changeEmailUseCase;
	private final ChangePasswordUseCase changePasswordUseCase;
	private final DeleteAccountUseCase deleteAccountUseCase;
	private final IdempotencyApplicationService idempotency;
	private final com.socialcoisa.rede.identity.application.AuthThrottle throttle;
	private final OAuthProperties oAuthProperties;
	private final com.socialcoisa.rede.identity.adapters.out.TurnstileValidator turnstile;
	private final com.socialcoisa.rede.identity.application.SignupChallengeService signupCaptcha;
	private final com.socialcoisa.rede.identity.application.NewDeviceNotifier newDeviceNotifier;
	private final com.socialcoisa.rede.identity.application.TwoFactorService twoFactorService;
	private final com.socialcoisa.rede.shared.security.IpBlockReader ipBlockReader;
	private final com.socialcoisa.rede.shared.security.SignupAttackGuard signupAttackGuard;
	private final com.socialcoisa.rede.profile.application.port.out.UserProfileRepository profiles;
	private final com.socialcoisa.rede.shared.security.ClientIpResolver ipResolver;
	private final tools.jackson.databind.ObjectMapper objectMapper;
	/** Cadastros abertos? Desligado, o POST /auth/signup responde 403 SIGNUP_DISABLED. */
	private final boolean signupEnabled;
	/** Login social desligável por ambiente (QA sem app OAuth configurado). */
	private final boolean googleLoginEnabled;
	private final com.socialcoisa.rede.shared.web.SessionCookies sessionCookies;

	AuthController(SignupUseCase signupUseCase, VerifyEmailUseCase verifyEmailUseCase,
			TwoFactorLoginUseCase twoFactorLoginUseCase, TwoFactorUseCase twoFactorUseCase,
			LoginUseCase loginUseCase, LogoutUseCase logoutUseCase,
			GetAuthenticatedUserUseCase getAuthenticatedUserUseCase,
			OAuthUseCase oAuthUseCase,
			RefreshSessionUseCase refreshSessionUseCase,
			RequestPasswordResetUseCase requestPasswordResetUseCase,
			ResetPasswordUseCase resetPasswordUseCase,
			ChangeEmailUseCase changeEmailUseCase,
			ChangePasswordUseCase changePasswordUseCase,
			DeleteAccountUseCase deleteAccountUseCase,
			IdempotencyApplicationService idempotency,
			OAuthProperties oAuthProperties,
			com.socialcoisa.rede.identity.application.AuthThrottle throttle,
			com.socialcoisa.rede.identity.adapters.out.TurnstileValidator turnstile,
			com.socialcoisa.rede.identity.application.SignupChallengeService signupCaptcha,
			com.socialcoisa.rede.identity.application.NewDeviceNotifier newDeviceNotifier,
			com.socialcoisa.rede.identity.application.TwoFactorService twoFactorService,
			com.socialcoisa.rede.shared.security.IpBlockReader ipBlockReader,
			com.socialcoisa.rede.shared.security.SignupAttackGuard signupAttackGuard,
			com.socialcoisa.rede.profile.application.port.out.UserProfileRepository profiles,
			com.socialcoisa.rede.shared.security.ClientIpResolver ipResolver,
			tools.jackson.databind.ObjectMapper objectMapper,
			@Value("${rede.auth.signup-enabled:true}") boolean signupEnabled,
			@Value("${rede.oauth.google.enabled:true}") boolean googleLoginEnabled,
			com.socialcoisa.rede.shared.web.SessionCookies sessionCookies) {
		this.signupUseCase = signupUseCase;
		this.verifyEmailUseCase = verifyEmailUseCase;
		this.twoFactorLoginUseCase = twoFactorLoginUseCase;
		this.twoFactorUseCase = twoFactorUseCase;
		this.loginUseCase = loginUseCase;
		this.logoutUseCase = logoutUseCase;
		this.getAuthenticatedUserUseCase = getAuthenticatedUserUseCase;
		this.oAuthUseCase = oAuthUseCase;
		this.refreshSessionUseCase = refreshSessionUseCase;
		this.requestPasswordResetUseCase = requestPasswordResetUseCase;
		this.resetPasswordUseCase = resetPasswordUseCase;
		this.changeEmailUseCase = changeEmailUseCase;
		this.changePasswordUseCase = changePasswordUseCase;
		this.deleteAccountUseCase = deleteAccountUseCase;
		this.idempotency = idempotency;
		this.throttle = throttle;
		this.oAuthProperties = oAuthProperties;
		this.turnstile = turnstile;
		this.signupCaptcha = signupCaptcha;
		this.newDeviceNotifier = newDeviceNotifier;
		this.twoFactorService = twoFactorService;
		this.ipBlockReader = ipBlockReader;
		this.signupAttackGuard = signupAttackGuard;
		this.profiles = profiles;
		this.ipResolver = ipResolver;
		this.objectMapper = objectMapper;
		this.signupEnabled = signupEnabled;
		this.googleLoginEnabled = googleLoginEnabled;
		this.sessionCookies = sessionCookies;
	}

	/** Bloqueio de IP pela moderacao (V115): nenhum fluxo de autenticacao emite sessao de IP banido. */
	private void exigeIpNaoBloqueado(String ip) {
		if (ipBlockReader.blocked(ip)) {
			throw new ApiException(403, "IP_BLOCKED",
				"Este endereco de rede esta bloqueado pela moderacao. Fale com o suporte se for um erro.");
		}
	}

	/**
	 * Configuração pública do captcha: o front decide entre o widget do Turnstile e o desafio
	 * próprio sem precisar de variável VITE_* no build. Só a chave pública do site sai daqui.
	 */
	@GetMapping("/config")
	AuthConfig config() {
		String mode;
		String siteKey;
		if (turnstile.isAvailable()) {
			mode = "TURNSTILE";
			siteKey = turnstile.siteKey();
		}
		else if (signupCaptcha.isEnabled()) {
			mode = "CHALLENGE";
			siteKey = null;
		}
		else {
			mode = "NONE";
			siteKey = null;
		}
		return new AuthConfig(mode, siteKey, googleLoginEnabled);
	}

	/** Desafio de soma para o cadastro (usado quando não há Turnstile configurado). */
	@GetMapping("/signup-challenge")
	SignupChallengeService.Challenge signupChallenge() {
		return signupCaptcha.issue();
	}

	@PostMapping("/signup")
	@RateLimiter(name = "auth")
	ResponseEntity<String> signup(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestHeader(value = "X-Device-Fingerprint", required = false) String deviceFingerprint,
			@RequestBody SignupRequest body,
			jakarta.servlet.http.HttpServletRequest request) {
		String ip = ipResolver.resolve(request);
		exigeIpNaoBloqueado(ip);
		// Contenção imediata: nome com o padrão do ataque não cria conta; repeated hits pause
		// signups from the IP (as contas "probe" de 20/10 e 22/10 nasciam em série do mesmo provedor).
		signupAttackGuard.checkSignup(body.name(), body.email(), ip);
		// An already registered e-mail goes through every gate below and gets the same 201 body as
		// a new signup; the service e-mails the existing account instead (see AuthService.signup).
		// Portao de cadastros: a operacao pode fechar a criacao de contas (a Mel pediu em
		// 2026-10-20). Antes de Turnstile/throttle: nao adianta o cliente resolver captcha.
		if (!signupEnabled) {
			throw new ApiException(403, "SIGNUP_DISABLED",
				"Os cadastros estao fechados no momento. Se voce ja tem conta, entre normalmente.");
		}
		// Honeypot: bots preenchem o campo "website" que humanos nunca veem.
		// Resposta fake (201) para nao dar dica ao atacante.
		if (body.website() != null && !body.website().isBlank()) {
			return fakeSignupAccepted(body.email());
		}
		throttle.exigeSignupPermitido(ip, deviceFingerprint);
		String scope = authScope(body.email());
		// Hash sem senha: a idempotência não pode virar um hash rápido de credencial no banco (E-03).
		String hash = idempotency.hash("signup:" + body.email().trim().toLowerCase(Locale.ROOT));
		return idempotency.execute(scope, "POST", "/api/auth/signup", idempotencyKey, hash, () -> {
			// Dentro do idempotente: repetir a MESMA requisição devolve a resposta em cache sem
			// queimar um captcha novo (Turnstile e desafio são de uso único).
			if (turnstile.isAvailable()) {
				turnstile.require(body.turnstileToken());
			}
			else {
				signupCaptcha.require(body.challengeId(), body.challengeAnswer());
			}
			return new CommandResponse(201, signupUseCase.signup(new SignupCommand(body.name(), "USER",
				body.email(), body.password(), body.acceptedTerms(), body.birthDate())));
		});
	}

	/**
	 * Confirma o e-mail a partir do link enviado no cadastro e ja devolve a sessao: quem confirma
	 * nao precisa entrar de novo. Token expirado (410), ja usado (409) e invalido (404) tem codigos
	 * proprios para a tela explicar o que aconteceu.
	 */
	@PostMapping("/email/verify")
	@RateLimiter(name = "auth")
	ResponseEntity<AuthResponse> verifyEmail(@RequestBody VerifyEmailRequest request) {
		return ResponseEntity.ok(verifyEmailUseCase.verifyEmail(request.token()));
	}

	/**
	 * Reenvia o link de confirmacao. Responde 202 mesmo quando o e-mail nao existe ou ja foi
	 * confirmado, para nao virar um oraculo de quais contas existem.
	 */
	@PostMapping("/email/resend")
	@RateLimiter(name = "email-sending")
	ResponseEntity<StatusResponse> resendVerification(@RequestBody ResendVerificationRequest request) {
		verifyEmailUseCase.resendVerification(request.email());
		return ResponseEntity.accepted().body(new StatusResponse(null, "VERIFICATION_SENT"));
	}

	/**
	 * Senha correta nao basta quando a conta tem segundo fator: nesse caso responde 202 com o desafio
	 * (`twoFactorRequired`), e a sessao sai em {@code /auth/2fa/verify}.
	 */
	@PostMapping("/login")
	@RateLimiter(name = "auth")
	ResponseEntity<?> login(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestHeader(value = "X-Device-Fingerprint", required = false) String deviceFingerprint,
			@RequestBody LoginRequest body,
			jakarta.servlet.http.HttpServletRequest httpRequest) {
		String ip = ipResolver.resolve(httpRequest);
		exigeIpNaoBloqueado(ip);
		// Login NÃO exige Turnstile: o widget invisível não gera token em tempo (bloqueador de
		// scripts, Cloudflare lento, submit rápido) e travava login de gente de verdade. A porta
		// fica fechada por IP bloqueado, limite por conta e travamento de tentativas — o captcha
		// anti-bot fica no cadastro, que é onde nascem as contas falsas.
		throttle.exigeLoginPermitido(body.email(), ip);
		// SEM idempotência persistida: a resposta contém access/refresh token e o hash do corpo
		// incluiria a senha (E-03/E-19). Repetir um login é seguro — gera outra sessão.
		try {
			LoginResult result = loginUseCase.login(new LoginCommand(body.email(), body.password(), ip));
			throttle.limpaLogin(body.email(), ip);
			if (result.requiresPasswordReset()) {
				return ResponseEntity.status(200).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
					.body(objectMapper.writeValueAsString(Map.of(
					"passwordExpired", true,
					"resetToken", result.resetToken())));
			}
			if (!result.requiresTwoFactor()) {
				newDeviceNotifier.check(result.session().user().id(), deviceFingerprint);
				// Tokens go out as HttpOnly cookies (AuthCookieAdvice), never in the body.
				return ResponseEntity.ok(result.session());
			}
			return ResponseEntity.status(202).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.body(objectMapper.writeValueAsString(Map.of(
				"twoFactorRequired", true,
				"challengeId", result.challenge().challengeId(),
				"method", result.challenge().method(),
				"expiresAt", result.challenge().expiresAt().toString())));
		}
		catch (ApiException falha) {
			if (falha.status() == 401) {
				throttle.registraFalhaLogin(body.email(), ip);
			}
			throw falha;
		}
	}

	/** Conclui o login com o codigo do segundo fator (ou com um codigo de recuperacao). */
	@PostMapping("/2fa/verify")
	@RateLimiter(name = "twofa")
	ResponseEntity<AuthResponse> verifyTwoFactor(
			@RequestHeader(value = "X-Device-Fingerprint", required = false) String deviceFingerprint,
			@RequestBody VerifyTwoFactorRequest body,
			jakarta.servlet.http.HttpServletRequest httpRequest) {
		exigeIpNaoBloqueado(ipResolver.resolve(httpRequest));
		AuthResponse auth = twoFactorLoginUseCase.verifyTwoFactor(body.challengeId(), body.code());
		newDeviceNotifier.check(auth.user().id(), deviceFingerprint);
		return ResponseEntity.ok(auth);
	}

	/** Reenvia o codigo por e-mail do desafio em andamento. */
	@PostMapping("/2fa/resend")
	@RateLimiter(name = "email-sending")
	ResponseEntity<StatusResponse> resendTwoFactor(@RequestBody ResendTwoFactorRequest request) {
		twoFactorLoginUseCase.resendTwoFactorCode(request.challengeId());
		return ResponseEntity.accepted().body(new StatusResponse(null, "CODE_SENT"));
	}

	@PostMapping("/logout")
	ResponseEntity<String> logout(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestHeader(value = "X-Session-Token", required = false) String token,
			@org.springframework.web.bind.annotation.CookieValue(name = com.socialcoisa.rede.shared.web.SessionCookies.REFRESH,
				required = false) String refreshCookie,
			jakarta.servlet.http.HttpServletResponse httpResponse) {
		sessionCookies.clear().forEach(value -> httpResponse.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE, value));
		// O escopo da idempotencia e chave de tabela: vai como HASH do token, nunca o token vivo
		// em texto puro (quem le a tabela — backup, DBA — nao ganha uma sessao com isso).
		String credential = StringUtils.hasText(token) ? token : refreshCookie;
		String scope = "session:" + (credential == null ? "anonymous" : idempotency.hash(credential.trim()));
		String hash = idempotency.hash(Map.of("token", scope));
		return idempotency.execute(scope, "POST", "/api/auth/logout", idempotencyKey, hash, () -> {
			logoutUseCase.logout(token, refreshCookie);
			return new CommandResponse(200, Map.of("status", "LOGGED_OUT"));
		});
	}

	@GetMapping("/me")
	Object me(@RequestHeader("X-Session-Token") String token) {
		return getAuthenticatedUserUseCase.getAuthenticatedUser(token);
	}

	@GetMapping("/oauth/{provider}/start")
	@RateLimiter(name = "auth")
	Object oauthStart(@PathVariable String provider) {
		OAuthProvider oauthProvider = parseProvider(provider);
		if (oauthProvider == OAuthProvider.GOOGLE && !googleLoginEnabled) {
			throw new ApiException(403, "OAUTH_PROVIDER_DISABLED",
				"Login com Google indisponivel neste ambiente.");
		}
		return oAuthUseCase.start(oauthProvider);
	}

	/**
	 * Inicia a vinculacao de um provedor a conta logada. O state guardado aponta para o usuario, e o
	 * callback que usar esse state vincula em vez de entrar.
	 */
	@PostMapping("/oauth/{provider}/link")
	@RateLimiter(name = "auth")
	Object oauthStartLink(@PathVariable String provider, @RequestHeader(value = "X-Session-Token", required = false) String token) {
		OAuthProvider oauthProvider = parseProvider(provider);
		String userId = requireUserId(token);
		return oAuthUseCase.startLink(userId, oauthProvider);
	}

	// ------------------------------------------------------------------ segundo fator (gestao)

	@GetMapping("/2fa/status")
	TwoFactorUseCase.TwoFactorStatus twoFactorStatus(
			@RequestHeader(value = "X-Session-Token", required = false) String token) {
		return twoFactorUseCase.status(requireUserId(token));
	}

	/** Comeca a configuracao do aplicativo authenticator (segredo pendente + URI otpauth). */
	@PostMapping("/2fa/totp/setup")
	@RateLimiter(name = "twofa")
	TwoFactorUseCase.TotpSetup startTotpSetup(
			@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestBody(required = false) PasswordConfirmationRequest request) {
		return twoFactorUseCase.startTotpSetup(requireUserId(token), request == null ? null : request.password());
	}

	@PostMapping("/2fa/totp/enable")
	@RateLimiter(name = "twofa")
	Map<String, Object> enableTotp(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestBody EnableTotpRequest request) {
		List<String> backupCodes = twoFactorUseCase.enableTotp(requireUserId(token), request.code(), request.password(), token);
		return Map.of("status", "TOTP_ENABLED", "backupCodes", backupCodes);
	}

	/** Desativar exige a senha: uma sessao roubada nao pode desligar o segundo fator sozinha. */
	@PostMapping("/2fa/totp/disable")
	@RateLimiter(name = "twofa")
	StatusResponse disableTotp(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestBody PasswordConfirmationRequest request) {
		twoFactorUseCase.disableTotp(requireUserId(token), request.password(), token);
		return new StatusResponse("2fa", "TOTP_DISABLED");
	}

	/** Envia o codigo que confirma a ativacao do segundo fator por e-mail. */
	@PostMapping("/2fa/email/request")
	@RateLimiter(name = "email-sending")
	ResponseEntity<StatusResponse> requestEmailOtp(
			@RequestHeader(value = "X-Session-Token", required = false) String token) {
		twoFactorUseCase.requestEmailOtpSetup(requireUserId(token));
		return ResponseEntity.accepted().body(new StatusResponse("2fa", "CODE_SENT"));
	}

	@PostMapping("/2fa/email/confirm")
	@RateLimiter(name = "twofa")
	StatusResponse confirmEmailOtp(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestBody TwoFactorCodeRequest request) {
		twoFactorUseCase.confirmEmailOtpSetup(requireUserId(token), request.code(), token);
		return new StatusResponse("2fa", "EMAIL_OTP_ENABLED");
	}

	@PostMapping("/2fa/email/disable")
	@RateLimiter(name = "twofa")
	StatusResponse disableEmailOtp(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestBody PasswordConfirmationRequest request) {
		twoFactorUseCase.disableEmailOtp(requireUserId(token), request.password(), token);
		return new StatusResponse("2fa", "EMAIL_OTP_DISABLED");
	}

	/** Provedores vinculados a conta (a tela mostra e permite desvincular). */
	/**
	 * Reset emergencial (23/10): limpa os segredos TOTP que a troca de infra deixou ilegiveis, para
	 * quem tem 2FA por app voltar a logar com e-mail + senha e reativar em /seguranca.
	 */
	@PostMapping("/admin/two-factor/reset-broken")
	Map<String, Boolean> resetBrokenTwoFactor(@RequestHeader("X-Session-Token") String sessionToken) {
		// Está sob /auth, então o interceptor de /admin não o cobre: exige admin explicitamente,
		// by the same server-side users.is_admin flag AdminGuard uses.
		String callerId = requireUserId(sessionToken);
		boolean admin = profiles.findById(callerId).map(profile -> profile.admin()).orElse(false);
		if (!admin) {
			AUDIT.warn("admin_two_factor_reset_denied userId={}", callerId);
			throw new ApiException(403, "FORBIDDEN", "Acesso restrito a administradores.");
		}
		int reset = twoFactorService.resetBrokenTotp();
		AUDIT.warn("admin_two_factor_reset userId={} accounts={}", callerId, reset);
		return Map.of("ok", true);
	}

	@GetMapping("/oauth/providers")
	Object oauthProviders(@RequestHeader(value = "X-Session-Token", required = false) String token) {
		return oAuthUseCase.linkedProviders(requireUserId(token));
	}

	@DeleteMapping("/oauth/{provider}/link")
	@RateLimiter(name = "auth")
	Object oauthUnlink(@PathVariable String provider, @RequestHeader(value = "X-Session-Token", required = false) String token) {
		OAuthProvider oauthProvider = parseProvider(provider);
		oAuthUseCase.unlink(requireUserId(token), oauthProvider);
		return new StatusResponse(provider, "UNLINKED");
	}

	/**
	 * Callback do provedor. O `state` e conferido no servidor (existe, e do mesmo provedor, dentro da
	 * validade e de uso unico) — o cookie nao serve para isso, porque o POST cross-site da Apple nem
	 * o envia com SameSite=Lax.
	 */
	@GetMapping("/oauth/{provider}/callback")
	@RateLimiter(name = "auth")
	Object oauthCallback(@PathVariable String provider, @RequestParam(required = false) String code,
			@RequestParam(required = false) String state, @RequestParam(required = false) String error) {
		// Este callback e uma NAVIGACAO do browser (o provedor redireciona para ca). Qualquer
		// falha vira redirect para a tela de callback do app com mensagem legivel — JSON cru
		// aqui deixa a pessoa olhando um erro sem saida (relato de suporte: "Requisicao
		// invalida" ao importar o perfil do LinkedIn).
		try {
			OAuthProvider oauthProvider = parseProvider(provider);
			if (StringUtils.hasText(error) || !StringUtils.hasText(code)) {
				return finishOAuthError(oauthProvider, error);
			}
			return finishOAuth(oAuthUseCase.callback(oauthProvider, code, state, null));
		}
		catch (ApiException e) {
			return finishOAuthError(parseProvider(provider), e.code());
		}
	}

	/**
	 * Apple envia o callback via POST (response_mode=form_post), com code/state/user no corpo.
	 */
	@PostMapping("/oauth/{provider}/callback")
	@RateLimiter(name = "auth")
	Object oauthCallbackPost(@PathVariable String provider, @RequestParam(required = false) String code,
			@RequestParam(required = false) String state,
			@RequestParam(required = false, name = "id_token") String idToken,
			@RequestParam(required = false, name = "user") String userPayload,
			@RequestParam(required = false) String error) {
		OAuthProvider oauthProvider = parseProvider(provider);
		if (StringUtils.hasText(error)) {
			return finishOAuthError(oauthProvider, error);
		}
		// Preferimos o code: a troca comprova que o token veio para este cliente, e o `nonce` do
		// id_token resultante e conferido contra o state.
		AuthResponse auth = StringUtils.hasText(code)
			? oAuthUseCase.callback(oauthProvider, code, state, userPayload)
			: oAuthUseCase.tokenLogin(oauthProvider, idToken, state);
		return finishOAuth(auth);
	}

	private String requireUserId(String token) {
		if (!StringUtils.hasText(token)) {
			throw new ApiException(401, "UNAUTHENTICATED", "Sessao invalida ou expirada.");
		}
		return getAuthenticatedUserUseCase.getAuthenticatedUser(token).id();
	}

	private Object finishOAuth(AuthResponse auth) {
		String redirectUrl = oAuthProperties.getFrontendRedirectUrl();
		if (StringUtils.hasText(redirectUrl)) {
			// No credential in the URL (history, logs, Referer): the session rides on the 302 cookies.
			return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl + "?status=ok"))
				.header(org.springframework.http.HttpHeaders.SET_COOKIE, sessionCookies.issue(auth.token(),
					auth.expiresAt(), auth.refreshToken(), auth.refreshExpiresAt()).toArray(String[]::new))
				.build();
		}
		return auth;
	}

	/**
	 * Cancelamento ou falha na tela do provedor volta para o app com um codigo legivel, em vez de
	 * estourar um erro tecnico no meio do redirect.
	 */
	private Object finishOAuthError(OAuthProvider provider, String error) {
		String code = StringUtils.hasText(error) ? error.trim().toUpperCase(Locale.ROOT) : "OAUTH_FAILED";
		String redirectUrl = oAuthProperties.getFrontendRedirectUrl();
		if (StringUtils.hasText(redirectUrl)) {
			return ResponseEntity.status(HttpStatus.FOUND)
				.location(URI.create(redirectUrl + "?error=" + urlEncode(code) + "&provider=" + provider.name()))
				.build();
		}
		throw new ApiException(401, "OAUTH_" + code, "Nao foi possivel concluir o login com " + provider.name() + ".");
	}

	private String urlEncode(String value) {
		return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
	}

	@PostMapping("/refresh")
	@RateLimiter(name = "refresh")
	Object refreshSession(@RequestBody(required = false) RefreshRequest request,
			@org.springframework.web.bind.annotation.CookieValue(name = com.socialcoisa.rede.shared.web.SessionCookies.REFRESH,
				required = false) String refreshCookie) {
		// Browsers send no body: the refresh token comes from the HttpOnly cookie.
		String refreshToken = request != null && StringUtils.hasText(request.refreshToken()) ? request.refreshToken()
			: refreshCookie;
		return refreshSessionUseCase.refresh(refreshToken);
	}

	@PostMapping("/oauth/{provider}/token")
	@RateLimiter(name = "auth")
	Object oauthToken(@PathVariable String provider, @RequestBody OAuthTokenRequest request) {
		OAuthProvider oauthProvider = parseProvider(provider);
		// O `state` (obtido em /oauth/{provider}/start) amarra o id_token ao pedido: sem ele o token
		// poderia ter sido emitido para outro fluxo e nao ha nonce para conferir.
		return oAuthUseCase.tokenLogin(oauthProvider, request.idToken(), request.state());
	}

	record OAuthTokenRequest(String idToken, String state) {
	}

	record RefreshRequest(String refreshToken) {
	}

	private OAuthProvider parseProvider(String value) {
		try {
			return OAuthProvider.valueOf(value.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new com.socialcoisa.rede.shared.web.ApiException(400, "INVALID_OAUTH_PROVIDER",
				"Provedor OAuth invalido: " + value);
		}
	}

	@PostMapping("/password/forgot")
	@RateLimiter(name = "email-sending")
	ResponseEntity<String> forgotPassword(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody ForgotPasswordRequest request) {
		String scope = authScope(request.email());
		String hash = idempotency.hash("forgot:" + request.email().trim().toLowerCase(Locale.ROOT));
		return idempotency.execute(scope, "POST", "/api/auth/password/forgot", idempotencyKey, hash,
			() -> {
				requestPasswordResetUseCase.requestReset(request.email());
				return new CommandResponse(202, Map.of("status", "RESET_REQUEST_ACCEPTED"));
			});
	}

	@PostMapping("/password/reset")
	@RateLimiter(name = "email-sending")
	ResponseEntity<String> resetPassword(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody ResetPasswordRequest request) {
		String scope = "reset:" + (StringUtils.hasText(request.token()) ? idempotency.hash(request.token().trim())
			: "anonymous");
		// Só o escopo (hash do token), nunca a senha nova.
		String hash = idempotency.hash(scope);
		return idempotency.execute(scope, "POST", "/api/auth/password/reset", idempotencyKey, hash,
			() -> {
				resetPasswordUseCase.reset(request.token(), request.password());
				return new CommandResponse(200, Map.of("status", "PASSWORD_RESET_ACCEPTED"));
			});
	}

	@PostMapping("/email/change")
	Object changeEmail(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestBody ChangeEmailRequest request) {
		changeEmailUseCase.changeEmail(token, request.email(), request.password());
		return ResponseEntity.accepted().body(new StatusResponse("me", "EMAIL_CONFIRMATION_SENT"));
	}

	/**
	 * Link do e-mail enviado ao NOVO endereço. É uma navegação do cliente de e-mail (sem Origin),
	 * por isso responde HTML e fica isento no gate de origem.
	 */
	@GetMapping(value = "/email/change/confirm", produces = org.springframework.http.MediaType.TEXT_HTML_VALUE)
	ResponseEntity<String> confirmEmailChange(@RequestParam(required = false) String token) {
		try {
			changeEmailUseCase.confirmEmailChange(token);
			return emailPage(200, "E-mail alterado",
				"Pronto! O novo endereço já é o da sua conta. Por segurança, entre novamente.");
		}
		catch (ApiException failure) {
			return emailPage(failure.status(), "Não foi possível confirmar",
				"Link inválido ou expirado.");
		}
	}

	private static ResponseEntity<String> emailPage(int status, String title, String message) {
		String html = "<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"UTF-8\">"
			+ "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"><title>"
			+ org.springframework.web.util.HtmlUtils.htmlEscape(title) + "</title></head>"
			+ "<body style=\"margin:0;background:#f8f7ff;font-family:system-ui,sans-serif;display:grid;"
			+ "place-items:center;min-height:100vh\"><div style=\"max-width:440px;background:#fff;"
			+ "border-radius:16px;padding:32px;margin:16px;text-align:center\"><h1 style=\"font-size:20px\">"
			+ org.springframework.web.util.HtmlUtils.htmlEscape(title) + "</h1><p style=\"color:#4b5563\">"
			+ org.springframework.web.util.HtmlUtils.htmlEscape(message) + "</p>"
			+ "<a href=\"https://enois.ia.br/seguranca\" style=\"color:#6366f1\">Ir para o Énóis</a>"
			+ "</div></body></html>";
		return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.TEXT_HTML).body(html);
	}

	@PostMapping("/password/change")
	@RateLimiter(name = "auth")
	Object changePassword(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestBody ChangePasswordRequest request) {
		changePasswordUseCase.changePassword(token, request.currentPassword(), request.newPassword());
		return new StatusResponse("me", "PASSWORD_CHANGED");
	}

	@PostMapping("/account/delete")
	Object deleteAccount(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestBody DeleteAccountRequest request) {
		deleteAccountUseCase.deleteAccount(token, request.password());
		return new StatusResponse("me", "ACCOUNT_DELETED");
	}

	record ChangeEmailRequest(String email, String password) {
	}

	record ChangePasswordRequest(String currentPassword, String newPassword) {
	}

	record DeleteAccountRequest(String password) {
	}

	record AuthConfig(String captchaMode, String turnstileSiteKey, boolean googleLoginEnabled) {
}

	/** Same shape as a real signup response, serialized by the JSON mapper. */
	private ResponseEntity<String> fakeSignupAccepted(String email) {
		String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
		return ResponseEntity.status(201)
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.body(objectMapper.writeValueAsString(Map.of("email", normalized, "verificationRequired", true,
				"emailSent", true)));
	}

	private String authScope(String email) {
		return "auth:" + (StringUtils.hasText(email) ? email.trim().toLowerCase(Locale.ROOT) : "anonymous");
	}

	record ForgotPasswordRequest(String email) {
	}

	record ResetPasswordRequest(String token, String password) {
	}

	record VerifyEmailRequest(String token) {
	}

	record ResendVerificationRequest(String email) {
	}

	record VerifyTwoFactorRequest(String challengeId, String code) {
	}

	record ResendTwoFactorRequest(String challengeId) {
	}

	record TwoFactorEnableRequest(String code, String password) {
	}

	record TwoFactorCodeRequest(String code) {
	}

	record EnableTotpRequest(String code, String password) {
	}

	record PasswordConfirmationRequest(String password) {
	}

}
