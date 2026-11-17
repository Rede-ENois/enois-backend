package com.socialcoisa.rede.shared.web;

import java.io.IOException;
import java.time.Clock;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.moderation.application.AdminAuthorityAudit;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Escopo persistido é negado por padrão, após o cookie e o alias v1 resolverem o pedido. */
@Component
@Order(4)
class SessaoModeracaoFilter extends OncePerRequestFilter {
	private static final PathPattern ADMINISTRATIVA = PathPatternParser.defaultInstance.parse("/admin/**");
	private static final PathPattern MODERACAO = PathPatternParser.defaultInstance.parse("/moderation/**");
	private static final Set<String> PERMITIDAS = Set.of("GET /me/sanctions", "POST /me/sanctions/appeals",
		"POST /auth/logout", "POST /auth/login", "POST /auth/2fa/verify", "POST /auth/2fa/resend");
	private final ObjectProvider<AuthSessionRepository> sessoes;
	private final ObjectProvider<SanctionGateReader> sancoes;
	private final ObjectProvider<AdminAuthorityAudit> auditoria;
	private final Clock relogio;
	private final com.socialcoisa.rede.shared.security.RateLimitSessions orcamento;
	private final com.socialcoisa.rede.shared.security.ClientIpResolver origens;

	SessaoModeracaoFilter(ObjectProvider<AuthSessionRepository> sessoes,
			ObjectProvider<SanctionGateReader> sancoes, Clock relogio,
			com.socialcoisa.rede.shared.security.RateLimitSessions orcamento,
			com.socialcoisa.rede.shared.security.ClientIpResolver origens,
			ObjectProvider<AdminAuthorityAudit> auditoria) {
		this.sessoes = sessoes;
		this.sancoes = sancoes;
		this.relogio = relogio;
		this.orcamento = orcamento;
		this.origens = origens;
		this.auditoria = auditoria;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest pedido, HttpServletResponse resposta, FilterChain cadeia)
			throws ServletException, IOException {
		String token = pedido.getHeader("X-Session-Token");
		String rota = pedido.getRequestURI().substring(pedido.getContextPath().length());
		if (!StringUtils.hasText(token) || permitida(pedido.getMethod(), rota)) {
			cadeia.doFilter(pedido, resposta);
			return;
		}
		if (!orcamento.prepareScopeLookup(token, origens.resolve(pedido))) {
			resposta.setHeader("Retry-After", "60");
			recusar(pedido, resposta, 429, "SESSION_LOOKUP_LIMIT", "Muitas verificacoes de sessao. Tente novamente em instantes.");
			return;
		}
		if (!sessoes.getObject().isSanctionsOnly(token.trim(), relogio.instant())) {
			cadeia.doFilter(pedido, resposta);
			return;
		}
		String titular = sessoes.getObject().findActiveSanctionsUserId(token.trim(), relogio.instant()).orElse(null);
		boolean suspenso = titular != null && sancoes.getObject().suspended(titular);
		auditarRecusa(titular, pedido);
		recusar(pedido, resposta, 403, suspenso ? "ACCOUNT_SUSPENDED" : "SESSION_RESTRICTED",
			"Esta sessao permite somente consultar sua decisao e recorrer.");
	}

	private void auditarRecusa(String titular, HttpServletRequest pedido) {
		if (titular == null) {
			return;
		}
		try {
			var caminho = RequestPath.segments(pedido);
			if (!ADMINISTRATIVA.matches(caminho) && !MODERACAO.matches(caminho)) {
				return;
			}
			auditoria.getObject().denied(titular, pedido.getMethod(), RequestPath.applicationPath(pedido),
				403, AdminAuthorityAudit.SESSION_RESTRICTED, origens.resolve(pedido));
		}
		catch (RuntimeException falha) {
			// A trilha registra suas falhas; a recusa nao pode depender da auditoria.
		}
	}

	private void recusar(HttpServletRequest pedido, HttpServletResponse resposta, int status, String codigo,
			String mensagem) throws IOException {
		resposta.setStatus(status);
		resposta.setHeader("Cache-Control", "no-store");
		TrustedOriginCors.apply(pedido, resposta);
		resposta.setContentType("application/json");
		resposta.setCharacterEncoding("UTF-8");
		resposta.getWriter().write("{\"code\":\"" + codigo + "\",\"message\":\"" + mensagem + "\"}");
	}

	private boolean permitida(String metodo, String rota) {
		return "OPTIONS".equals(metodo) || PERMITIDAS.contains(metodo + " " + rota)
			|| (("GET".equals(metodo) || "POST".equals(metodo))
				&& rota.matches("/auth/oauth/(google|apple|linkedin)/(start|callback|token)"));
	}
}
