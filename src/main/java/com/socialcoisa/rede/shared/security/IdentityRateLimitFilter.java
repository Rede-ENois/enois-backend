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

	/**
	 * Caminho → ação do limitador. POST é o que cria conta, gasta senha ou dispara e-mail; o GET do
	 * desafio entra porque emitir é o passo anterior ao cadastro (sem teto, o cliente podia pedir
	 * desafios à vontade dentro da cota de cadastro).
	 */
	private static final Map<String, String> RULES = Map.ofEntries(
		Map.entry("/auth/signup", IpRateLimiter.SIGNUP),
		Map.entry("/auth/login", IpRateLimiter.LOGIN),
		Map.entry("/auth/password/forgot", IpRateLimiter.PASSWORD),
		Map.entry("/auth/password/reset", IpRateLimiter.PASSWORD),
		Map.entry("/auth/email/resend", IpRateLimiter.EMAIL),
		Map.entry("/auth/2fa/resend", IpRateLimiter.EMAIL),
		Map.entry("/auth/2fa/email/request", IpRateLimiter.EMAIL),
		Map.entry("/auth/2fa/verify", IpRateLimiter.TWO_FACTOR),
		Map.entry("/auth/2fa/email/confirm", IpRateLimiter.TWO_FACTOR),
		Map.entry("/auth/2fa/totp/enable", IpRateLimiter.TWO_FACTOR),
		Map.entry("/auth/refresh", IpRateLimiter.REFRESH));
	private static final Map<String, String> GET_RULES = Map.of(
		"/auth/signup-challenge", IpRateLimiter.ISSUE_CHALLENGE);

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
		if (action == null && "GET".equalsIgnoreCase(request.getMethod())) {
			action = pathRule(request, GET_RULES);
		}
		if (action != null && !limiter.tryAcquire(action, ipResolver.resolve(request))) {
			RateLimitResponses.send(request, response, 300, "RATE_LIMITED",
				"Muitas tentativas em sequencia. Espere alguns minutos e tente de novo.");
			return;
		}
		filterChain.doFilter(request, response);
	}

	private String actionFor(HttpServletRequest request) {
		if (!"POST".equalsIgnoreCase(request.getMethod())) {
			return null;
		}
		return pathRule(request, RULES);
	}

	/** A rota da regra, já sem o prefixo de contexto (`/api` em produção, vazio nos testes). */
	private static String pathRule(HttpServletRequest request, Map<String, String> rules) {
		String path = request.getRequestURI();
		String context = request.getContextPath();
		if (context != null && !context.isEmpty() && path.startsWith(context)) {
			path = path.substring(context.length());
		}
		String normalized = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
		return rules.get(normalized);
	}



}
