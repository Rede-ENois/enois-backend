package com.socialcoisa.rede.shared.web;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Trava central de autorização para TODO `/admin/**`, independente do controller lembrar.
 *
 * Os controllers já chamam `adminGuard.requireAdmin`, mas essa checagem é manual: uma rota admin
 * nova (ou um método novo num controller existente) pode esquecer. O interceptor fecha essa
 * lacuna antes do MVC: sem sessão é 401; sessão válida que não está na allowlist de admin é 403.
 *
 * A checagem do controller continua valendo como defesa em profundidade. O custo é uma consulta
 * de sessão + uma de perfil por requisição admin, aceitável para uma superfície pequena.
 */
@Component
class AdminRouteInterceptor implements HandlerInterceptor {

	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminRouteInterceptor(CurrentUserResolver currentUserResolver, AdminGuard adminGuard) {
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		// Preflight nao carrega sessao: quem responde e o CORS. Sem esta saida o OPTIONS de
		// qualquer /admin caia aqui, o resolver lancava 401 e o navegador recebia 500 no
		// preflight — o painel de moderacao nunca abria (visto em producao em 2026-10-23).
		if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
			return true;
		}
		if (!isAdminPath(request)) {
			return true;
		}
		CurrentUser user = currentUserResolver.resolve(request.getHeader("X-Session-Token"));
		adminGuard.requireAdmin(user);
		return true;
	}

	private boolean isAdminPath(HttpServletRequest request) {
		String path = request.getRequestURI();
		String context = request.getContextPath();
		if (context != null && !context.isEmpty() && path.startsWith(context)) {
			path = path.substring(context.length());
		}
		return path.startsWith("/admin/") || path.equals("/admin");
	}
}
