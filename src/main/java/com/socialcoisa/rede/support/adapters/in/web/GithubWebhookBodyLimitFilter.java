package com.socialcoisa.rede.support.adapters.in.web;

import java.io.IOException;

import org.springframework.core.Ordered;
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
 * Teto do corpo do webhook do GitHub antes do binding para {@code byte[]}.
 *
 * <p>A rota precisa do corpo inteiro para conferir o HMAC, mas o {@code @RequestBody byte[]}
 * materializa tudo antes de qualquer checagem. O GitHub manda Content-Length; acima do teto a
 * requisicao e recusada sem ler o corpo. Eventos de issue ficam muito abaixo disto.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
class GithubWebhookBodyLimitFilter extends OncePerRequestFilter {

	private static final long MAX_BODY = 2L * 1024 * 1024;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().endsWith("/webhooks/github");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > MAX_BODY) {
			response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write("{\"code\":\"WEBHOOK_TOO_LARGE\","
				+ "\"message\":\"Corpo do webhook acima do limite.\"}");
			return;
		}
		chain.doFilter(request, response);
	}
}
