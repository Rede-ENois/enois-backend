package com.socialcoisa.rede.shared.web;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Fail-closed para escrita: todo POST/PUT/PATCH/DELETE exige sessão, exceto os fluxos públicos
 * explicitamente listados (entrar, cadastrar, recuperar senha, confirmar e-mail, OAuth e o token
 * interno dos jogos). Rota nova de escrita nasce protegida mesmo que o controller esqueça.
 *
 * O controller continua validando dono/papel; esta é a rede de segurança. `/admin/**` fica com o
 * {@link AdminRouteInterceptor}, que é mais restritivo (exige admin, não só sessão).
 */
@Component
class WriteRouteInterceptor implements HandlerInterceptor {

	/** Fluxos que existem justamente para quem ainda não tem sessão. */
	/**
	 * Pacote visivel de proposito: o teste que varre TODAS as rotas de escrita
	 * (AutomatedWritePathsTests) compara esta lista com o catalogo esperado — se alguem abrir
	 * mais um fluxo publico sem querer, o teste quebra.
	 */
	static final List<String> PUBLIC_WRITES = List.of(
		"/auth/signup",
		"/auth/login",
		"/auth/refresh",
		// Logout only revokes the credentials the caller sends; it must work once the access token
		// has expired, or the refresh cookie would outlive the "logout".
		"/auth/logout",
		"/auth/email/verify",
		"/auth/email/resend",
		"/auth/password/forgot",
		"/auth/password/reset",
		"/auth/2fa/verify",
		"/auth/2fa/resend",
		"/auth/2fa/email/request",
		"/auth/2fa/email/confirm",
		// Formulário público de contribuição da landing: limitado por IP e pelo gate de origem.
		"/contributors/applications",
		"/auth/oauth/*/callback",
		"/auth/oauth/*/token",
		"/games/tokens/validate",
		// GitHub não tem sessão no app: a autenticação é o HMAC X-Hub-Signature-256 do webhook.
		"/webhooks/github",
		// Didit idem: sem sessão, autenticação por HMAC X-Signature sobre o corpo cru. O webhook
		// não aprova ninguém — só sinaliza para o backend reler o desfecho no provedor.
		"/webhooks/didit",
		// RFC 8058 one-click unsubscribe: the mail client POSTs without a session. The encrypted
		// token in the URL is the authorization and the only effect is turning e-mails off.
		"/blogs/descadastrar",
		"/notifications/digest/unsubscribe",
		// Servidor de reunião perguntando se alguém pode entrar numa chamada ou numa sessão de
		// mentoria: servidor não tem sessão de navegador. A credencial é o segredo compartilhado,
		// conferido no controller.
		"/calls/*/gateway/entry/*",
		"/mentoring/*/gateway/entry/*",
		// CI de produção publicando as "Novidades" do deploy: servidor não tem sessão; a
		// credencial é o token de serviço X-Deploy-Token, conferido no controller (fail-closed).
		"/internal/novidades-deploy",
		// Rede cooperativa: um nó não tem sessão de navegador. O enroll é autenticado pelo token de
		// provisionamento de uso único e o heartbeat pela API key do nó (X-Node-Id + X-Node-Key),
		// conferidos no controller — fail-closed como as rotas internas.
		"/network/nodes/enroll",
		"/network/nodes/heartbeat",
		// Fase 2: o nó devolve o resultado da tarefa. Mesma credencial de nó (X-Node-Id + X-Node-Key),
		// conferida no controller. O GET /network/tasks/next não entra aqui: só escrita exige sessão.
		"/network/tasks/*/result",
		// Fase 3: o nó de borda confirma o que sincronizou e pede a reconciliação. Mesma credencial
		// de nó; as leituras de conteúdo são GET e não passam por aqui.
		"/network/content/*/*/synced",
		"/network/content/reconcile",
		// Fase 3: o nó de mídia devolve o arquivo transcodificado. Mesma credencial de nó; além
		// dela o controller exige o perfil NODE_MEDIA e a tarefa designada a este nó.
		"/network/media/*/result");

	private final AntPathMatcher matcher = new AntPathMatcher();
	private final CurrentUserResolver currentUserResolver;

	WriteRouteInterceptor(CurrentUserResolver currentUserResolver) {
		this.currentUserResolver = currentUserResolver;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!isWrite(request.getMethod())) {
			return true;
		}
		String path = path(request);
		if (path.startsWith("/admin/") || path.equals("/admin")) {
			return true;
		}
		if (PUBLIC_WRITES.stream().anyMatch(pattern -> matcher.match(pattern, path))) {
			return true;
		}
		currentUserResolver.resolve(request.getHeader("X-Session-Token"));
		return true;
	}

	private boolean isWrite(String method) {
		return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
			|| "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
	}

	private String path(HttpServletRequest request) {
		String path = request.getRequestURI();
		String context = request.getContextPath();
		if (context != null && !context.isEmpty() && path.startsWith(context)) {
			path = path.substring(context.length());
		}
		return path;
	}
}
