package com.socialcoisa.rede.identity.adapters.in.web;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Locale;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
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
import com.socialcoisa.rede.identity.application.port.in.SignupUseCase;
import com.socialcoisa.rede.identity.domain.OAuthProvider;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.StatusResponse;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

@RestController
@RequestMapping("/auth")
class AuthController {

	private final SignupUseCase signupUseCase;
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
	private final OAuthProperties oAuthProperties;

	AuthController(SignupUseCase signupUseCase, LoginUseCase loginUseCase, LogoutUseCase logoutUseCase,
			GetAuthenticatedUserUseCase getAuthenticatedUserUseCase,
			OAuthUseCase oAuthUseCase,
			RefreshSessionUseCase refreshSessionUseCase,
			RequestPasswordResetUseCase requestPasswordResetUseCase,
			ResetPasswordUseCase resetPasswordUseCase,
			ChangeEmailUseCase changeEmailUseCase,
			ChangePasswordUseCase changePasswordUseCase,
			DeleteAccountUseCase deleteAccountUseCase,
			IdempotencyApplicationService idempotency,
			OAuthProperties oAuthProperties) {
		this.signupUseCase = signupUseCase;
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
		this.oAuthProperties = oAuthProperties;
	}

	@PostMapping("/signup")
	@RateLimiter(name = "auth")
	ResponseEntity<String> signup(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody SignupRequest request) {
		String scope = authScope(request.email());
		String hash = idempotency.hash(request);
		return idempotency.execute(scope, "POST", "/api/auth/signup", idempotencyKey, hash, () -> new CommandResponse(201,
			signupUseCase.signup(new SignupCommand(request.name(), "USER", request.email(), request.password()))));
	}

	@PostMapping("/login")
	@RateLimiter(name = "auth")
	ResponseEntity<String> login(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody LoginRequest request) {
		String scope = authScope(request.email());
		String hash = idempotency.hash(request);
		return idempotency.execute(scope, "POST", "/api/auth/login", idempotencyKey, hash, () -> new CommandResponse(200,
			loginUseCase.login(new LoginCommand(request.email(), request.password()))));
	}

	@PostMapping("/logout")
	ResponseEntity<String> logout(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestHeader(value = "X-Session-Token", required = false) String token) {
		String scope = "session:" + (token == null ? "anonymous" : token.trim());
		String hash = idempotency.hash(Map.of("token", scope));
		return idempotency.execute(scope, "POST", "/api/auth/logout", idempotencyKey, hash, () -> {
			logoutUseCase.logout(token);
			return new CommandResponse(200, Map.of("status", "LOGGED_OUT"));
		});
	}

	@GetMapping("/me")
	Object me(@RequestHeader("X-Session-Token") String token) {
		return getAuthenticatedUserUseCase.getAuthenticatedUser(token);
	}

	private static final String OAUTH_STATE_COOKIE = "oauth_state";

	@Value("${rede.oauth.state-cookie-secure:true}")
	private boolean stateCookieSecure;

	@GetMapping("/oauth/{provider}/start")
	@RateLimiter(name = "auth")
	Object oauthStart(@PathVariable String provider, HttpServletResponse response) {
		OAuthProvider oauthProvider = parseProvider(provider);
		OAuthStartResponse start = oAuthUseCase.start(oauthProvider);
		ResponseCookie cookie = ResponseCookie.from(OAUTH_STATE_COOKIE, start.state())
			.httpOnly(true)
			.secure(stateCookieSecure)
			.sameSite("Lax")
			.path("/")
			.maxAge(Duration.ofMinutes(15))
			.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
		return start;
	}

	@GetMapping("/oauth/{provider}/callback")
	@RateLimiter(name = "auth")
	Object oauthCallback(@PathVariable String provider, @RequestParam(required = false) String code,
			@RequestParam(required = false) String state,
			@CookieValue(value = OAUTH_STATE_COOKIE, required = false) String cookieState,
			HttpServletResponse response) {
		OAuthProvider oauthProvider = parseProvider(provider);
		if (!StringUtils.hasText(state) || !StringUtils.hasText(cookieState) || !state.equals(cookieState)) {
			throw new ApiException(401, "INVALID_OAUTH_STATE", "State de OAuth invalido ou expirado.");
		}
		response.addHeader(HttpHeaders.SET_COOKIE,
			ResponseCookie.from(OAUTH_STATE_COOKIE, "").httpOnly(true).secure(stateCookieSecure).sameSite("Lax")
				.path("/").maxAge(Duration.ZERO).build().toString());
		AuthResponse auth = oAuthUseCase.callback(oauthProvider, code, state);
		return finishOAuth(auth);
	}

	/**
	 * Apple envia o callback via POST (response_mode=form_post), com code/state/id_token no corpo.
	 */
	@PostMapping("/oauth/{provider}/callback")
	@RateLimiter(name = "auth")
	Object oauthCallbackPost(@PathVariable String provider, @RequestParam(required = false) String code,
			@RequestParam(required = false) String state,
			@RequestParam(required = false, name = "id_token") String idToken,
			@CookieValue(value = OAUTH_STATE_COOKIE, required = false) String cookieState,
			HttpServletResponse response) {
		OAuthProvider oauthProvider = parseProvider(provider);
		if (!StringUtils.hasText(state) || !StringUtils.hasText(cookieState) || !state.equals(cookieState)) {
			throw new ApiException(401, "INVALID_OAUTH_STATE", "State de OAuth invalido ou expirado.");
		}
		response.addHeader(HttpHeaders.SET_COOKIE,
			ResponseCookie.from(OAUTH_STATE_COOKIE, "").httpOnly(true).secure(stateCookieSecure).sameSite("Lax")
				.path("/").maxAge(Duration.ZERO).build().toString());
		// Apple entrega o id_token diretamente; o code exigiria troca com client-secret JWT.
		AuthResponse auth = StringUtils.hasText(idToken)
			? oAuthUseCase.tokenLogin(oauthProvider, idToken)
			: oAuthUseCase.callback(oauthProvider, code, state);
		return finishOAuth(auth);
	}

	private Object finishOAuth(AuthResponse auth) {
		String redirectUrl = oAuthProperties.getFrontendRedirectUrl();
		if (StringUtils.hasText(redirectUrl)) {
			String location = redirectUrl
				+ "?token=" + auth.token()
				+ "&refreshToken=" + auth.refreshToken()
				+ "&expiresAt=" + auth.expiresAt()
				+ "&refreshExpiresAt=" + auth.refreshExpiresAt();
			return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
		}
		return auth;
	}

	@PostMapping("/refresh")
	Object refreshSession(@RequestBody RefreshRequest request) {
		return refreshSessionUseCase.refresh(request.refreshToken());
	}

	@PostMapping("/oauth/{provider}/token")
	@RateLimiter(name = "auth")
	Object oauthToken(@PathVariable String provider, @RequestBody OAuthTokenRequest request) {
		OAuthProvider oauthProvider = parseProvider(provider);
		return oAuthUseCase.tokenLogin(oauthProvider, request.idToken());
	}

	record OAuthTokenRequest(String idToken) {
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
	ResponseEntity<String> forgotPassword(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody ForgotPasswordRequest request) {
		String scope = authScope(request.email());
		String hash = idempotency.hash(request);
		return idempotency.execute(scope, "POST", "/api/auth/password/forgot", idempotencyKey, hash,
			() -> {
				requestPasswordResetUseCase.requestReset(request.email());
				return new CommandResponse(202, Map.of("status", "RESET_REQUEST_ACCEPTED"));
			});
	}

	@PostMapping("/password/reset")
	ResponseEntity<String> resetPassword(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody ResetPasswordRequest request) {
		String scope = "reset:" + (StringUtils.hasText(request.token()) ? request.token().trim() : "anonymous");
		String hash = idempotency.hash(request);
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
		return new StatusResponse("me", "EMAIL_CHANGED");
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

	private String authScope(String email) {
		return "auth:" + (StringUtils.hasText(email) ? email.trim().toLowerCase(Locale.ROOT) : "anonymous");
	}

	record ForgotPasswordRequest(String email) {
	}

	record ResetPasswordRequest(String token, String password) {
	}
}
