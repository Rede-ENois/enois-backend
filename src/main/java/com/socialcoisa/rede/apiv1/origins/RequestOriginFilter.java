package com.socialcoisa.rede.apiv1.origins;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.socialcoisa.rede.shared.web.RequestPath;
import com.socialcoisa.rede.shared.web.TrustedOriginCors;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Toda requisição precisa de uma origem cadastrada para ser atendida.
 *
 * Duas classes de origem:
 * <ul>
 * <li><b>Confiável</b> — as do próprio app ({@code CORS_ALLOWED_ORIGINS}) e os padrões de
 * {@code rede.origins.trusted} (ex.: {@code https://*.b7cloud.com.br}). Aceitas em todas as rotas,
 * inclusive {@code /admin/**} e {@code /moderation/**}.</li>
 * <li><b>Cadastrada</b> — origem de terceiro registrada em {@code /developers/origins}. Aceita nas
 * rotas normais, mas NUNCA em admin/moderação, e pode ser bloqueada automaticamente pelo
 * {@link OriginAbuseGuard} se gerar recusas em série.</li>
 * </ul>
 *
 * Navegador manda {@code Origin}; chamadas de servidor (SSR, curl, integrações) mandam
 * {@code X-Client-Origin}. Se o {@code Origin} vier, ele é a autoridade e o {@code X-Client-Origin}
 * não o substitui — só serve para detectar tentativa de falsificação (contada como suspeita).
 *
 * Fluxos de terceiros/infra sem origem do app (webhook do GitHub, callbacks OAuth/gov.br,
 * health/version, páginas públicas, mídia GET e descadastro) ficam isentos.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class RequestOriginFilter extends OncePerRequestFilter {

	private static final PathPattern ADMIN_PATH = PathPatternParser.defaultInstance.parse("/admin/**");
	private static final PathPattern MODERATION_PATH = PathPatternParser.defaultInstance.parse("/moderation/**");

	static final String CLIENT_ORIGIN_HEADER = "X-Client-Origin";

	private final DeveloperOriginService origins;
	private final OriginAbuseGuard abuse;
	private final Set<String> staticOrigins;
	private final Set<String> trustedPatterns;
	private final boolean enforce;

	RequestOriginFilter(DeveloperOriginService origins, OriginAbuseGuard abuse,
			@Value("${rede.cors.allowed-origins:https://enois.ia.br}") String allowed,
			@Value("${rede.origins.trusted:https://*.b7cloud.com.br}") String trusted,
			@Value("${rede.origins.enforce:true}") boolean enforce) {
		this.origins = origins;
		this.abuse = abuse;
		this.staticOrigins = OriginPatterns.parse(allowed);
		this.trustedPatterns = OriginPatterns.parse(trusted);
		this.enforce = enforce;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// Marked even when enforcement is off: filters that answer early reflect only these origins.
		boolean trustedBrowserOrigin = false;
		String browserOrigin = request.getHeader("Origin");
		if (StringUtils.hasText(browserOrigin) && isTrusted(matchForm(browserOrigin))) {
			trustedBrowserOrigin = true;
			TrustedOriginCors.markTrusted(request, browserOrigin);
		}
		if (!enforce) {
			chain.doFilter(request, response);
			return;
		}
		String path = RequestPath.applicationPath(request);
		String method = request.getMethod();

		// Preflight é sempre respondido, de qualquer origem: ele não executa nada nem devolve dado,
		// e a operação real continua passando por este filtro. Recusar o OPTIONS só transforma a
		// recusa em falha de CORS no navegador — a ação aparece como "sem conexão" sem nunca ter
		// chegado ao servidor.
		if ("OPTIONS".equalsIgnoreCase(method)) {
			String preflightOrigin = request.getHeader("Origin");
			if (StringUtils.hasText(preflightOrigin)) {
				setCors(response, preflightOrigin);
				// O front chama a API cross-origin com o cookie de sessão: sem ecoar a credencial
				// aqui o navegador descarta o preflight ("Allow-Credentials ... must be 'true'") e a
				// tela fica sem dados. Só a origem do próprio app recebe isso — origem de terceiro
				// cadastrada continua saindo sem credencial.
				if (trustedBrowserOrigin) {
					response.setHeader("Access-Control-Allow-Credentials", "true");
				}
			}
			preflight(request, response);
			return;
		}

		// Fluxos controlados por terceiros ou infraestrutura que não têm origem do app.
		if (bypass(method, path)) {
			chain.doFilter(request, response);
			return;
		}

		String origin = request.getHeader("Origin");
		String clientOrigin = request.getHeader(CLIENT_ORIGIN_HEADER);
		String declared = StringUtils.hasText(origin) ? origin : clientOrigin;

		if (!StringUtils.hasText(declared)) {
			if (allowsNoOrigin(method, path)) {
				chain.doFilter(request, response);
				return;
			}
			reject(response, "ORIGIN_REQUIRED",
				"Toda requisicao precisa de origem cadastrada. O navegador envia Origin; chamadas de "
					+ "servidor devem enviar X-Client-Origin com uma origem cadastrada.");
			return;
		}

		String normalized = matchForm(declared);
		boolean trusted = isTrusted(normalized);
		boolean registered = !trusted && origins.allowedOrigins().contains(normalized);

		// Origin presente + X-Client-Origin diferente: tentativa de declarar outra origem.
		if (StringUtils.hasText(origin) && StringUtils.hasText(clientOrigin)
				&& !matchForm(origin).equals(matchForm(clientOrigin))
				&& origins.allowedOrigins().contains(matchForm(origin))) {
			abuse.recordFailure(matchForm(origin));
		}

		if (!trusted && !registered) {
			if (origins.isBlocked(normalized)) {
				reject(response, "ORIGIN_BLOCKED",
					"Esta origem foi bloqueada por atividade suspeita. Remova-a em /developers/origins.");
			}
			else {
				reject(response, "ORIGIN_NOT_ALLOWED",
					"Cadastre a origem do seu app em /developers/origins para usar a API.");
			}
			return;
		}

		// Admin e moderação: só a origem do frontend (confiável), nunca origem de terceiro.
		if (isAdminPath(request) && !trusted) {
			if (StringUtils.hasText(origin)) {
				setCors(response, origin);
			}
			abuse.recordFailure(normalized);
			reject(response, "ORIGIN_NOT_TRUSTED",
				"Rotas de administracao e moderacao aceitam apenas a origem do frontend.");
			return;
		}

		if (trusted) {
			// O preflight da PROPRIA origem tambem e respondido aqui. Em `/admin` o OPTIONS caia no
			// handler, que exige sessao: o navegador recebia 500 no preflight e o painel de
			// moderacao nunca abria (`NetworkError` no console). O resto segue no CORS estatico.
			if ("OPTIONS".equalsIgnoreCase(method) && StringUtils.hasText(origin)) {
				setCors(response, origin);
				// Only the app's own origins may send the HttpOnly session cookie.
				response.setHeader("Access-Control-Allow-Credentials", "true");
				if (preflight(request, response)) {
					return;
				}
			}
			chain.doFilter(request, response);
			return;
		}

		// Origem de terceiro cadastrada: responde o CORS e conta recusas para o bloqueio automático.
		if (StringUtils.hasText(origin)) {
			setCors(response, origin);
			if (preflight(request, response)) {
				return;
			}
		}
		chain.doFilter(request, response);
		int status = response.getStatus();
		if (status == 401 || status == 403 || status == 429) {
			abuse.recordFailure(normalized);
		}
	}

	private boolean isTrusted(String normalized) {
		return OriginPatterns.matches(normalized, staticOrigins)
				|| OriginPatterns.matches(normalized, trustedPatterns);
	}

	/** Responde o preflight de uma origem de terceiro cadastrada. */
	private static boolean preflight(HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		if (!"OPTIONS".equalsIgnoreCase(request.getMethod())) {
			return false;
		}
		response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
		String requested = request.getHeader("Access-Control-Request-Headers");
		response.setHeader("Access-Control-Allow-Headers", StringUtils.hasText(requested) ? requested : "*");
		response.setHeader("Access-Control-Max-Age", "600");
		response.setStatus(204);
		return true;
	}

	private static void setCors(HttpServletResponse response, String origin) {
		response.setHeader("Access-Control-Allow-Origin", origin);
		response.setHeader("Vary", "Origin");
	}

	/**
	 * Rotas que pertencem a outro sistema (GitHub, provedores OAuth, gov.br) ou à infraestrutura.
	 * Elas autenticam por conta própria (HMAC, state/nonce) e não têm a origem do app.
	 */
	private static boolean bypass(String method, String path) {
		if (path.equals("/health") || path.equals("/version")) {
			return true;
		}
		if (path.startsWith("/actuator")) {
			return true;
		}
		if (path.startsWith("/webhooks/")) {
			return true;
		}
		if (path.startsWith("/identity/govbr/")) {
			return true;
		}
		// GET de início e callbacks OAuth: navegação/redirect do provedor, sem Origin do app.
		return path.startsWith("/auth/oauth/")
				&& ("GET".equalsIgnoreCase(method) || path.endsWith("/callback"));
	}

	/**
	 * Conteúdo público que pode ser aberto direto no navegador, carregado como imagem ou clicado
	 * de um e-mail. Se vier uma origem, ela ainda é validada antes.
	 */
	private static boolean allowsNoOrigin(String method, String path) {
		if (path.startsWith("/pages/")) {
			return true;
		}
		if (path.startsWith("/media/") && "GET".equalsIgnoreCase(method)) {
			return true;
		}
		// A midia servida pelo proprio app (proxy do bucket): `<img>` e `<video>` nao mandam Origin.
		if (path.startsWith("/storage/") && ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))) {
			return true;
		}
		// Figurinha de chat: mesmo caso — o `<img>` do texto e da grade nao manda Origin, e a imagem e
		// conteudo publico de leitura. As rotas de DADOS do catalogo (`/emotes`, `/emotes/index`)
		// continuam exigindo origem.
		if (path.startsWith("/emotes/image/") && ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))) {
			return true;
		}
		// Link de confirmação do novo e-mail: clique no cliente de e-mail, sem Origin.
		if (path.equals("/auth/email/change/confirm") && "GET".equalsIgnoreCase(method)) {
			return true;
		}
		// Links de descadastro (newsletter do blog e resumo semanal): clique no cliente de e-mail.
		return path.equals("/blogs/descadastrar") || path.equals("/notifications/digest/unsubscribe");
	}

	/** Rotas do painel: somente a origem confiável do frontend. */
	private static boolean isAdminPath(HttpServletRequest request) {
		var path = RequestPath.segments(request);
		return ADMIN_PATH.matches(path) || MODERATION_PATH.matches(path);
	}

	private static void reject(HttpServletResponse response, String code, String message) throws IOException {
		response.setStatus(403);
		// Um CDN/proxy não pode guardar o bloqueio e devolvê-lo para uma origem válida depois.
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Vary", "Origin");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
	}

	/** Forma de comparação: esquema/host minúsculos e sem barra final. */
	static String matchForm(String origin) {
		String clean = origin == null ? "" : origin.trim().toLowerCase(Locale.ROOT);
		while (clean.endsWith("/")) {
			clean = clean.substring(0, clean.length() - 1);
		}
		return clean;
	}
}
