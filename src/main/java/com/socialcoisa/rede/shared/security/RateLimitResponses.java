package com.socialcoisa.rede.shared.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 429 dos filtros, com os cabeçalhos de CORS que o navegador exige.
 *
 * <p>Os filtros de limite rodam ANTES do tratamento de CORS do MVC. Um 429 sem
 * {@code Access-Control-Allow-Origin} não é lido como "muitas requisições": o navegador reporta
 * falha de CORS e o app mostra "não foi possível..." sem status nenhum — foi assim que a reação
 * apareceu como "componente quebrado", sem nenhum POST no painel de rede. A origem só é refletida
 * quando o próprio app a declarou; continua sendo o filtro de origem que decide o que é confiável.
 */
final class RateLimitResponses {

	private RateLimitResponses() {
	}

	/** Escreve 429 com Retry-After e CORS refletido (quando há origem declarada). */
	static void send(HttpServletRequest request, HttpServletResponse response, int retryAfterSeconds,
			String code, String message) throws IOException {
		response.setStatus(429);
		response.setHeader("Retry-After", Integer.toString(Math.max(1, retryAfterSeconds)));
		String origin = request.getHeader("Origin");
		if (StringUtils.hasText(origin)) {
			response.setHeader("Access-Control-Allow-Origin", origin);
			response.addHeader("Vary", "Origin");
		}
		String requested = request.getHeader("Access-Control-Request-Headers");
		response.setHeader("Access-Control-Allow-Headers",
			StringUtils.hasText(requested) ? requested : "content-type,x-client-origin,x-session-token,idempotency-key,x-device-id");
		response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
	}
}
