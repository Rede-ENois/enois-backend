package com.socialcoisa.rede.identity.adapters.in.web;

import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.identity.adapters.out.TurnstileValidator;
import com.socialcoisa.rede.identity.application.AuthThrottle;
import com.socialcoisa.rede.identity.application.SignupChallengeService;
import com.socialcoisa.rede.identity.application.SignupCommand;
import com.socialcoisa.rede.identity.application.port.in.SignupUseCase;
import com.socialcoisa.rede.shared.security.IpBlockReader;
import com.socialcoisa.rede.shared.security.SignupAttackGuard;
import com.socialcoisa.rede.shared.web.ApiException;

@Component
public class CadastroHttp {

	private final SignupUseCase cadastro;
	private final IdempotencyApplicationService idempotencia;
	private final AuthThrottle limite;
	private final TurnstileValidator turnstile;
	private final SignupChallengeService captcha;
	private final IpBlockReader ips;
	private final SignupAttackGuard protecao;
	private final ObjectMapper json;
	private final boolean habilitado;

	public CadastroHttp(SignupUseCase cadastro, IdempotencyApplicationService idempotencia, AuthThrottle limite,
			TurnstileValidator turnstile, SignupChallengeService captcha, IpBlockReader ips,
			SignupAttackGuard protecao, ObjectMapper json,
			@Value("${rede.auth.signup-enabled:true}") boolean habilitado) {
		this.cadastro = cadastro;
		this.idempotencia = idempotencia;
		this.limite = limite;
		this.turnstile = turnstile;
		this.captcha = captcha;
		this.ips = ips;
		this.protecao = protecao;
		this.json = json;
		this.habilitado = habilitado;
	}

	public Configuracao configuracao(boolean loginGoogleHabilitado) {
		if (turnstile.isAvailable()) {
			return new Configuracao("TURNSTILE", turnstile.siteKey(), loginGoogleHabilitado);
		}
		return new Configuracao(captcha.isEnabled() ? "CHALLENGE" : "NONE", null, loginGoogleHabilitado);
	}

	public SignupChallengeService.Challenge desafio(String sessao, String dispositivo) {
		return captcha.issue(sessao, dispositivo);
	}

	public ResponseEntity<String> cadastrar(SignupRequest corpo, String chave, String sessao, String dispositivo,
			String ip) {
		if (ips.blocked(ip)) {
			throw new ApiException(403, "IP_BLOCKED",
				"Este endereco de rede esta bloqueado pela moderacao. Fale com o suporte se for um erro.");
		}
		protecao.checkSignup(corpo.name(), corpo.email(), ip, dispositivo);
		if (!habilitado) {
			throw new ApiException(403, "SIGNUP_DISABLED",
				"Os cadastros estao fechados no momento. Se voce ja tem conta, entre normalmente.");
		}
		String email = corpo.email() == null ? "" : corpo.email().trim().toLowerCase(Locale.ROOT);
		if (corpo.website() != null && !corpo.website().isBlank()) {
			return ResponseEntity.status(201).contentType(MediaType.APPLICATION_JSON)
				.body(json.writeValueAsString(Map.of("email", email, "verificationRequired", true, "emailSent", true)));
		}
		limite.exigeSignupPermitido(ip, dispositivo);
		if (email.isEmpty()) {
			throw new ApiException(400, "INVALID_EMAIL", "Email invalido.");
		}
		// A repetição consulta o resultado antes de consumir a prova de uso único.
		String hash = idempotencia.hash("signup:" + email);
		return idempotencia.execute("auth:" + email, "POST", "/api/auth/signup", chave, hash, () -> {
			if (turnstile.isAvailable()) {
				turnstile.require(corpo.turnstileToken());
			} else {
				captcha.require(corpo.challengeId(), corpo.challengeAnswer(), sessao, dispositivo);
			}
			return new CommandResponse(201, cadastro.signup(new SignupCommand(corpo.name(), "USER",
				corpo.email(), corpo.password(), corpo.acceptedTerms(), corpo.birthDate())));
		});
	}

	public record Configuracao(String captchaMode, String turnstileSiteKey, boolean googleLoginEnabled) {}
}
