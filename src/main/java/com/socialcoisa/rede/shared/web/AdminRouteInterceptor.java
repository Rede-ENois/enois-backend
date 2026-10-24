package com.socialcoisa.rede.shared.web;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.socialcoisa.rede.moderation.application.ModeratorService;

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
	private final ModeratorService moderators;

	/**
	 * Caminhos de /admin que o painel de moderação abre para MODERADORES (além do admin):
	 * filas, recursos, disputas, ações de usuário, IPs e o painel de monitoramento. Tudo o que
	 * não está aqui permanece exclusivo do admin — inclusive a candidatura e a gestão de
	 * moderadores, que nem aparecem nesta lista.
	 */
	private static final List<String> CAMINHOS_DE_MODERADOR = List.of(
		"/admin/appeals", "/admin/support", "/admin/emails",
		"/admin/carteira/disputas", "/admin/blocked-ips", "/admin/monitoring");

	private static final List<String> ACOES_DE_USUARIO = List.of("sanction", "sanitize", "ips");

	AdminRouteInterceptor(CurrentUserResolver currentUserResolver, AdminGuard adminGuard,
			ModeratorService moderators) {
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
		this.moderators = moderators;
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
		String path = caminho(request);
		if (abertoAoModerador(path)) {
			if (!adminGuard.isAdmin(user) && !moderators.isModerator(user.id().value())) {
				throw new ApiException(403, "FORBIDDEN", "Acesso restrito a moderadores.");
			}
			return true;
		}
		adminGuard.requireAdmin(user);
		return true;
	}

	private boolean abertoAoModerador(String path) {
		for (String prefixo : CAMINHOS_DE_MODERADOR) {
			if (path.startsWith(prefixo)) {
				return true;
			}
		}
		if (path.startsWith("/admin/users/")) {
			for (String acao : ACOES_DE_USUARIO) {
				if (path.endsWith("/" + acao)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean isAdminPath(HttpServletRequest request) {
		String path = caminho(request);
		return path.startsWith("/admin/") || path.equals("/admin");
	}

	private String caminho(HttpServletRequest request) {
		String path = request.getRequestURI();
		String context = request.getContextPath();
		if (context != null && !context.isEmpty() && path.startsWith(context)) {
			path = path.substring(context.length());
		}
		return path;
	}
}
