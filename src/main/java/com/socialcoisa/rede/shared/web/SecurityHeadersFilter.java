package com.socialcoisa.rede.shared.web;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Headers de seguranca para as respostas da API.
 *
 * A API so devolve JSON, entao uma CSP restritiva e segura — exceto nas paginas de documentacao
 * (Swagger UI), que ficam fora das diretivas quando habilitadas para uso interno.
 */
@Component
class SecurityHeadersFilter extends OncePerRequestFilter {

	private static final String STRICT_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (isDocumentationPath(request.getRequestURI())) {
			response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
		}
		else {
			response.setHeader("Content-Security-Policy", STRICT_CSP);
		}
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("X-Frame-Options", "DENY");
		response.setHeader("Referrer-Policy", "no-referrer");
		response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
		// O trafego chega pelo ALB (HTTPS); includeSubDomains protege os subdominios do mesmo host.
		response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
		filterChain.doFilter(request, response);
	}

	private boolean isDocumentationPath(String path) {
		return path.startsWith("/swagger-ui")
			|| path.startsWith("/v3/api-docs")
			|| path.endsWith("/openapi.json");
	}
}