package com.socialcoisa.rede.shared.web;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.servlet.HandlerInterceptor;

import com.socialcoisa.rede.moderation.application.AdminAuthorityAudit;
import com.socialcoisa.rede.shared.security.ClientIpResolver;

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
 *
 * The same lock covers `/moderation/**`: every route there is moderator work (admin or approved
 * moderator), except the two any signed-in member uses to see and apply for the role.
 *
 * <p>Toda recusa decidida aqui vira uma linha na trilha de auditoria com o motivo (só admin, só
 * moderador ou sem sessão) e o IP resolvido — a resposta ao cliente não muda: continua o mesmo 401
 * ou 403 genérico, sem dizer qual regra faltou.</p>
 */
@Component
class AdminRouteInterceptor implements HandlerInterceptor {

	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;
	private final ModeratorGuard moderatorGuard;
	private final ObjectProvider<AdminAuthorityAudit> denials;
	private final ObjectProvider<ClientIpResolver> ipResolver;

	/**
	 * Caminhos de /admin que o painel de moderação abre para MODERADORES (além do admin):
	 * filas, recursos, disputas, ações de usuário, IPs e o painel de monitoramento. Tudo o que
	 * não está aqui permanece exclusivo do admin — inclusive a candidatura e a gestão de
	 * moderadores, que nem aparecem nesta lista.
	 */
	private static final PathPattern ADMIN_PATH = pattern("/admin/**");

	private static final PathPattern MODERATION_PATH = pattern("/moderation/**");

	/** /moderation routes any signed-in member reaches: their own eligibility and the application. */
	private static final List<PathPattern> MODERATION_OPEN_TO_MEMBERS = List.of(
		pattern("/moderation/me"), pattern("/moderation/applications"));

	private static final List<PathPattern> CAMINHOS_DE_MODERADOR = List.of(
		"/admin/appeals/**", "/admin/support/**", "/admin/suggestions/**", "/admin/emails/**",
		"/admin/blocked-ips/**", "/admin/monitoring/**").stream()
		.map(AdminRouteInterceptor::pattern).toList();

	/** Subcaminhos de /admin/support que continuam exclusivos do admin (infraestrutura). */
	private static final List<PathPattern> SUPPORT_ADMIN_ONLY = List.of(pattern("/admin/support/github/**"));

	private static final List<PathPattern> ACOES_DE_USUARIO = List.of("sanction", "sanitize", "ips")
		.stream().map(action -> pattern("/admin/users/*/" + action)).toList();

	/**
	 * A trilha e o resolvedor de IP entram por {@link ObjectProvider}: o interceptor é criado junto
	 * com o contexto WebMvc, antes de quem depende de repositório estar pronto, e a resolução só
	 * acontece na recusa — o caminho autorizado não paga por ela.
	 */
	AdminRouteInterceptor(CurrentUserResolver currentUserResolver, AdminGuard adminGuard,
			ModeratorGuard moderatorGuard, ObjectProvider<AdminAuthorityAudit> denials,
			ObjectProvider<ClientIpResolver> ipResolver) {
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
		this.moderatorGuard = moderatorGuard;
		this.denials = denials;
		this.ipResolver = ipResolver;
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
		if (MODERATION_PATH.matches(path)) {
			CurrentUser user = userOf(request);
			if (MODERATION_OPEN_TO_MEMBERS.stream().noneMatch(pattern -> pattern.matches(path))) {
				require(() -> moderatorGuard.requireModerator(user), request, request.getMethod(),
					AdminAuthorityAudit.MODERATOR_ONLY);
			}
			return true;
		}
		if (!ADMIN_PATH.matches(path)) {
			return true;
		}
		CurrentUser user = userOf(request);
		if (abertoAoModerador(path)) {
			require(() -> moderatorGuard.requireModerator(user), request, request.getMethod(),
				AdminAuthorityAudit.MODERATOR_ONLY);
			return true;
		}
		require(() -> adminGuard.requireAdmin(user), request, request.getMethod(), AdminAuthorityAudit.ADMIN_ONLY);
		return true;
	}

	/**
	 * Resolve a sessão sem que o 401 escape antes da linha de auditoria: sem sessão a recusa também
	 * é registrada, com o motivo de sessão ausente, e o erro sobe igual ao de antes.
	 */
	private CurrentUser userOf(HttpServletRequest request) {
		try {
			return currentUserResolver.resolve(request.getHeader("X-Session-Token"));
		}
		catch (ApiException refused) {
			refuse(request, request.getMethod(), refused, AdminAuthorityAudit.SESSION_MISSING);
			throw refused;
		}
	}

	/** Executa a guarda; se ela recusar, a recusa é registrada e o MESMO erro segue para o cliente. */
	private void require(Runnable guard, HttpServletRequest request, String method, String reason) {
		try {
			guard.run();
		}
		catch (ApiException refused) {
			refuse(request, method, refused, reason);
			throw refused;
		}
	}

	private void refuse(HttpServletRequest request, String method, ApiException refused, String gate) {
		try {
			denials.getObject().denied(
				currentUserResolver.activeUserId(request.getHeader("X-Session-Token")).orElse(null),
				method, RequestPath.applicationPath(request), refused.status(), reasonOf(refused, gate),
				ipResolver.getObject().resolve(request));
		}
		catch (RuntimeException failure) {
			// A auditoria é observabilidade, não autorização: uma falha aqui não pode transformar a
			// recusa em 500 nem deixar a requisição passar. A própria trilha já loga a falha.
		}
	}

	/** Motivo registrado: sem sessão é 401; com sessão, a regra que faltou. */
	private static String reasonOf(ApiException refused, String gate) {
		return refused.status() == 401 ? AdminAuthorityAudit.SESSION_MISSING : gate;
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