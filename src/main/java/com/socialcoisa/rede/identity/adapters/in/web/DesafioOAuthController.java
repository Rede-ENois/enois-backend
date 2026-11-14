package com.socialcoisa.rede.identity.adapters.in.web;

import java.time.Clock;

import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.identity.application.AuthResponse.EtapaDoisFatores;
import com.socialcoisa.rede.identity.application.port.out.TwoFactorRepository;
import com.socialcoisa.rede.identity.domain.TwoFactorChallenge;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.SessionCookies;

/** Retoma a segunda etapa do OAuth sem transportar desafio ou credencial na URL. */
@RestController
class DesafioOAuthController {
	private final TwoFactorRepository desafios;
	private final Clock relogio;

	DesafioOAuthController(TwoFactorRepository desafios, Clock relogio) {
		this.desafios = desafios;
		this.relogio = relogio;
	}

	@GetMapping("/auth/2fa/challenge")
	@io.github.resilience4j.ratelimiter.annotation.RateLimiter(name = "twofa")
	EtapaDoisFatores consultar(@CookieValue(name = SessionCookies.TWO_FACTOR, required = false) String id,
			jakarta.servlet.http.HttpServletResponse resposta) {
		resposta.setHeader("Cache-Control", "no-store");
		if (id == null || id.isBlank()) {
			throw ausente();
		}
		return desafios.findChallenge(id).filter(d -> !d.isConsumed() && !d.isExpired(relogio.instant()))
			.filter(d -> d.attempts() < TwoFactorChallenge.MAX_ATTEMPTS).map(EtapaDoisFatores::from)
			.orElseThrow(this::ausente);
	}

	private ApiException ausente() {
		return new ApiException(401, "TWO_FACTOR_CHALLENGE_NOT_FOUND", "Inicie novamente o login para confirmar o segundo fator.");
	}
}
