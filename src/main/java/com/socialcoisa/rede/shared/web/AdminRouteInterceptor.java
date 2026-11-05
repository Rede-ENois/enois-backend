package com.socialcoisa.rede.shared.web;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
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
	private final ModeratorGuard moderatorGuard;

	/**
	 * Caminhos de /admin que o painel de moderação abre para MODERADORES (além do admin):
	 * filas, recursos, disputas, ações de usuário, IPs e o painel de monitoramento. Tudo o que
	 * não está aqui permanece exclusivo do admin — inclusive a candidatura e a gestão de
	 * moderadores, que nem aparecem nesta lista.
	 */
	private static final PathPattern ADMIN_PATH = pattern("/admin/**");

	private static final List<PathPattern> CAMINHOS_DE_MODERADOR = List.of(
		"/admin/appeals/**", "/admin/support/**", "/admin/suggestions/**", "/admin/emails/**",
		"/admin/blocked-ips/**", "/admin/monitoring/**").stream()
		.map(AdminRouteInterceptor::pattern).toList();

	/** Subcaminhos de /admin/support que continuam exclusivos do admin (infraestrutura). */
	private static final List<PathPattern> SUPPORT_ADMIN_ONLY = List.of(pattern("/admin/support/github/**"));

	private static final List<PathPattern> ACOES_DE_USUARIO = List.of("sanction", "sanitize", "ips")
		.stream().map(action -> pattern("/admin/users/*/" + action)).toList();

	AdminRouteInterceptor(CurrentUserResolver currentUserResolver, AdminGuard adminGuard,
			ModeratorGuard moderatorGuard) {
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
		this.moderatorGuard = moderatorGuard;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		// Preflight nao carrega sessao: quem responde e o CORS. Sem esta saida o OPTIONS de
		// qualquer /admin caia aqui, o resolver lancava 401 e o navegador recebia 500 no
		// preflight — o painel de moderacao nunca abria (visto em producao em 2026-10-23).
		if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
			return true;
		}
		PathContainer path = RequestPath.segments(request);
		if (!ADMIN_PATH.matches(path)) {
			return true;
		}
		CurrentUser user = currentUserResolver.resolve(request.getHeader("X-Session-Token"));
		if (abertoAoModerador(path)) {
			moderatorGuard.requireModerator(user);
			return true;
		}
		adminGuard.requireAdmin(user);
		return true;
	}

	private boolean abertoAoModerador(PathContainer path) {
		if (SUPPORT_ADMIN_ONLY.stream().anyMatch(pattern -> pattern.matches(path))) {
			return false;
		}
		return CAMINHOS_DE_MODERADOR.stream().anyMatch(pattern -> pattern.matches(path))
			|| ACOES_DE_USUARIO.stream().anyMatch(pattern -> pattern.matches(path));
	}

	private static PathPattern pattern(String value) {
		return PathPatternParser.defaultInstance.parse(value);
	}
}
