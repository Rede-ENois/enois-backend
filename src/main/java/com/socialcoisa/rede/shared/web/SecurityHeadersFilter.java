package com.socialcoisa.rede.shared.web;

import java.io.IOException;
import java.util.Arrays;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
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

	private static final String SESSION_COOKIE = "enois_session";

	private static final String STRICT_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

	/**
	 * Câmera e microfone ficam abertos para a própria página e para o servidor de reunião que a
	 * gente hospeda — é de onde a pessoa entra na chamada. Nenhum outro domínio entra na lista: a
	 * política continua fechando a mídia para terceiros.
	 *
	 * A origem vai entre aspas porque a gramática da política exige: sem elas o navegador descarta
	 * o item e a lista fica só com `self`. `self` é palavra-chave e não se cita.
	 */
	private final String permissionsPolicy;

	SecurityHeadersFilter() {
		this("");
	}

	SecurityHeadersFilter(@org.springframework.beans.factory.annotation.Value("${rede.calls.epapo.url:}") String callUrl) {
		String hosts = "";
		if (callUrl != null && !callUrl.isBlank()) {
			try {
				java.net.URI uri = java.net.URI.create(callUrl.trim());
				if (uri.getScheme() != null && uri.getHost() != null) {
					hosts = " \"" + uri.getScheme() + "://" + uri.getHost()
						+ (uri.getPort() > 0 ? ":" + uri.getPort() : "") + "\"";
				}
			}
			catch (IllegalArgumentException malformed) {
				// URL inválida não abre nada: a política fica só com `self`.
			}
		}
		String media = "(self" + hosts + ")";
		this.permissionsPolicy = "geolocation=(), microphone=" + media + ", camera=" + media
			+ ", display-capture=" + media;
	}

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
		response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
		response.setHeader("Cross-Origin-Resource-Policy", "same-site");
		response.setHeader("Referrer-Policy", "no-referrer");
		response.setHeader("Permissions-Policy", permissionsPolicy);
		// O trafego chega pelo ALB (HTTPS); includeSubDomains protege os subdominios do mesmo host.
		response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
		if (isAuthenticated(request)) {
			// Per-user data must not be kept by the browser or shared caches.
			response.setHeader("Cache-Control", "no-store");
		}
		filterChain.doFilter(request, response);
	}

	private boolean isAuthenticated(HttpServletRequest request) {
		if (request.getHeader("X-Session-Token") != null) {
			return true;
		}
		Cookie[] cookies = request.getCookies();
		return cookies != null && Arrays.stream(cookies).anyMatch(c -> SESSION_COOKIE.equals(c.getName()));
	}

	private boolean isDocumentationPath(String path) {
		return path.startsWith("/swagger-ui")
			|| path.startsWith("/v3/api-docs")
			|| path.endsWith("/openapi.json");
	}
}