package com.socialcoisa.rede.shared.security;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Aplica o limite por IP nas rotas de identidade — cadastro, entrada, senha, e-mail e segundo fator.
 *
 * Por que um filtro e não uma anotação em cada endpoint: assim **não dá para esquecer**. Rota nova
 * dentro de `/auth` entra na proteção pela regra de caminho, e o teste cobre o mapa de rotas.
 *
 * A resposta é 429 com o mesmo formato de erro do resto da API, sem dizer quantas tentativas faltam
 * nem qual é o teto — informar o teto ajuda quem está atacando a calibrar o ataque.
 */
@Component
@Order(2)
class IdentityRateLimitFilter extends OncePerRequestFilter {

	/** Caminho → ação do limitador. Só POST: é o que cria conta, gasta senha ou dispara e-mail. */
	private static final Map<String, String> RULES = Map.of(
		"/auth/signup", IpRateLimiter.SIGNUP,
		"/auth/login", IpRateLimiter.LOGIN,
		"/auth/password/forgot", IpRateLimiter.PASSWORD,
		"/auth/password/reset", IpRateLimiter.PASSWORD,
		"/auth/email/resend", IpRateLimiter.EMAIL,
		"/auth/2fa/resend", IpRateLimiter.EMAIL,
		"/auth/2fa/email/request", IpRateLimiter.EMAIL,
		"/auth/2fa/verify", IpRateLimiter.TWO_FACTOR,
		"/auth/2fa/email/confirm", IpRateLimiter.TWO_FACTOR,
		"/auth/2fa/totp/enable", IpRateLimiter.TWO_FACTOR);

	private final IpRateLimiter limiter;
	private final ClientIpResolver ipResolver;

	IdentityRateLimitFilter(IpRateLimiter limiter, ClientIpResolver ipResolver) {
		this.limiter = limiter;
		this.ipResolver = ipResolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String action = actionFor(request);
		if (action != null && !limiter.tryAcquire(action, ipResolver.resolve(request))) {
			RateLimitResponses.send(request, response, 300, "RATE_LIMITED",
				"Muitas tentativas em sequencia. Espere alguns minutos e tente de novo.");
			return;
		}
		filterChain.doFilter(request, response);
	}

	/** A rota da regra, já sem o prefixo de contexto (`/api` em produção, vazio nos testes). */
	private String actionFor(HttpServletRequest request) {
		if (!"POST".equalsIgnoreCase(request.getMethod())) {
			return null;
		}
		String path = request.getRequestURI();
		String context = request.getContextPath();
		if (context != null && !context.isEmpty() && path.startsWith(context)) {
			path = path.substring(context.length());
		}
		String normalized = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
		return RULES.get(normalized);
	}



}
