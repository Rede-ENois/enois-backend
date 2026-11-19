package com.socialcoisa.rede.identity.adapters.in.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.identity.application.AuthResponse;
import com.socialcoisa.rede.identity.application.AuthThrottle;
import com.socialcoisa.rede.identity.application.LoginCommand;
import com.socialcoisa.rede.identity.application.LoginResult;
import com.socialcoisa.rede.identity.application.NewDeviceNotifier;
import com.socialcoisa.rede.identity.application.port.in.LoginUseCase;
import com.socialcoisa.rede.identity.application.port.in.LogoutUseCase;
import com.socialcoisa.rede.identity.application.port.in.RefreshSessionUseCase;
import com.socialcoisa.rede.identity.application.port.in.TwoFactorLoginUseCase;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.security.IpBlockReader;
import com.socialcoisa.rede.shared.web.ApiException;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import tools.jackson.databind.ObjectMapper;

@Component
public class SessaoHttp {

	private final LoginUseCase loginUseCase;
	private final LogoutUseCase logoutUseCase;
	private final RefreshSessionUseCase refreshSessionUseCase;
	private final TwoFactorLoginUseCase twoFactorLoginUseCase;
	private final AuthThrottle throttle;
	private final NewDeviceNotifier newDeviceNotifier;
	private final IpBlockReader ipBlockReader;
	private final IdempotencyApplicationService idempotency;
	private final ObjectMapper objectMapper;

	SessaoHttp(LoginUseCase loginUseCase, LogoutUseCase logoutUseCase, RefreshSessionUseCase refreshSessionUseCase,
			TwoFactorLoginUseCase twoFactorLoginUseCase, AuthThrottle throttle, NewDeviceNotifier newDeviceNotifier,
			IpBlockReader ipBlockReader, IdempotencyApplicationService idempotency, ObjectMapper objectMapper) {
		this.loginUseCase = loginUseCase;
		this.logoutUseCase = logoutUseCase;
		this.refreshSessionUseCase = refreshSessionUseCase;
		this.twoFactorLoginUseCase = twoFactorLoginUseCase;
		this.throttle = throttle;
		this.newDeviceNotifier = newDeviceNotifier;
		this.ipBlockReader = ipBlockReader;
		this.idempotency = idempotency;
		this.objectMapper = objectMapper;
	}

	@RateLimiter(name = "auth")
	public ResponseEntity<?> login(LoginRequest body, String deviceFingerprint, String ip) {
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

	@RateLimiter(name = "twofa")
	public AuthResponse verificar(String challenge, String codigo, String deviceFingerprint, String ip) {
		exigeIpNaoBloqueado(ip);
		AuthResponse auth = twoFactorLoginUseCase.verifyTwoFactor(challenge, codigo);
		newDeviceNotifier.check(auth.user().id(), deviceFingerprint);
		return auth;
	}

	@RateLimiter(name = "refresh")
	public AuthResponse renovar(String token) {
		return refreshSessionUseCase.refresh(token);
	}

	public ResponseEntity<String> logout(String token, String refreshCookie, String idempotencyKey) {
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

	private void exigeIpNaoBloqueado(String ip) {
		if (ipBlockReader.blocked(ip)) {
			throw new ApiException(403, "IP_BLOCKED",
				"Este endereco de rede esta bloqueado pela moderacao. Fale com o suporte se for um erro.");
		}
	}
}
