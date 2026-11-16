package com.socialcoisa.rede.shared.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.socialcoisa.rede.moderation.application.AdminAuthorityAudit;
import com.socialcoisa.rede.shared.security.ClientIpResolver;
import com.socialcoisa.rede.shared.security.Hashes;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Linha de base dos acessos administrativos: um 401/403 em {@code /admin/**} ou
 * {@code /moderation/**} deixa de ser invisível mesmo quando o controller nem chega a ser chamado.
 *
 * <p>O interceptor administrativo cobre a autorização decidida por ele (e grava a recusa com o
 * motivo); falta o que fica ANTES dele: rota que não existe, cabeçalho de sessão ausente (401 pelo
 * {@link GlobalExceptionHandler}) e escrita recusada pelo {@link WriteRouteInterceptor}. Este filtro
 * conta as MUITAS requisições sem contexto, mede quanto cada uma repetiu e — só quando a repetição
 * passa do limiar — grava UMA linha na trilha de auditoria, com escopo próprio
 * ({@code admin-baseline}).</p>
 *
 * <p><b>Por que a linha só sai no limiar.</b> A trilha tem chave primária e é a tabela mais quente
 * do sistema: gravar uma linha por recusa transformaria varredura de rota em carga de escrita, que é
 * justamente o que quem varre quer. A contagem é em memória por pod (mapa com teto), e a
 * identidade anônima é o PAR método + rota + impressão do cliente e do corpo — nunca um IP puro, que
 * uma rede inteira compartilha (a mesma razão do limite por aparelho no cadastro). A recusa com
 * sessão válida já foi registrada pelo interceptor.</p>
 *
 * <p>A impressão é SHA-256 de um segredo de servidor mais a assinatura do cliente, ou da sessão
 * quando ela está válida: guardar o IP ou o token em claro num mapa em memória não é necessário para
 * contar, e o segredo impede que alguém calcule a impressão de fora.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
class AdminAccessBaselineFilter extends OncePerRequestFilter {

	static final String ALERT_ATTRIBUTE = "rede.security.adminDenialAlert";

	private static final PathPattern ADMIN_PATH = PathPatternParser.defaultInstance.parse("/admin/**");
	private static final PathPattern MODERATION_PATH = PathPatternParser.defaultInstance.parse("/moderation/**");

	private final ObjectProvider<CurrentUserResolver> users;
	private final ClientIpResolver ipResolver;
	private final ObjectProvider<AdminDenialCounter> counter;
	private final ObjectProvider<AdminAuthorityAudit> audit;
	private final Clock clock;
	private final String secret;
	private final int maxBody;

	/**
	 * O resolvedor de sessão, a contagem e a trilha entram por {@link ObjectProvider}: o filtro nasce
	 * antes do contexto completo, e um bean cuja cadeia chega ao EntityManager (o resolvedor chega
	 * pelo perfil) derruba o boot inteiro (armadilha já vista neste repositório). Quem só responde
	 * 200 nunca paga por resolvê-los.
	 */
	AdminAccessBaselineFilter(ObjectProvider<CurrentUserResolver> users, ClientIpResolver ipResolver,
			ObjectProvider<AdminDenialCounter> counter, ObjectProvider<AdminAuthorityAudit> audit, Clock clock,
			@Value("${rede.security.identity-secret:}") String identitySecret,
			@Value("${rede.security.duplicate-post-max-body-bytes:65536}") int maxBody) {
		this.users = users;
		this.ipResolver = ipResolver;
		this.counter = counter;
		this.audit = audit;
		this.clock = clock;
		this.secret = identitySecret == null ? "" : identitySecret;
		this.maxBody = Math.max(1024, maxBody);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		PathContainer path = RequestPath.segments(request);
		return !(ADMIN_PATH.matches(path) || MODERATION_PATH.matches(path));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
			// Preflight não carrega sessão nem executa nada: quem responde é o CORS.
			chain.doFilter(request, response);
			return;
		}
		// A assinatura do envio é lida agora, porque o interceptor (mais adiante) pode recusar antes
		// do corpo chegar ao controller. O corpo é devolvido intacto a quem vem depois.
		byte[] body = bodyOf(request);
		HttpServletRequest effective = body == null ? request : new CachedBodyRequest(request, body);
		chain.doFilter(effective, response);
		int status = response.getStatus();
		if (status != 401 && status != 403) {
			return;
		}
		String path = RequestPath.applicationPath(request);
		String userId = users.getObject().activeUserId(request.getHeader("X-Session-Token")).orElse("");
		String identity = identityOf(request, body, userId);
		AdminDenialCounter.Decision decision = counter.getObject().windowed(identity, clock.millis());
		if (!decision.alert()) {
			return;
		}
		request.setAttribute(ALERT_ATTRIBUTE, true);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("scope", "admin-baseline");
		metadata.put("status", status);
		metadata.put("route", routeOf(path));
		metadata.put("countInWindow", decision.countInWindow());
		metadata.put("clientIp", ipResolver.resolveNetwork(request));
		audit.getObject().record(userId, ruleOf(path), metadata);
	}

	private byte[] bodyOf(HttpServletRequest request) throws IOException {
		long declared = request.getContentLengthLong();
		if (declared > maxBody) {
			return null;
		}
		String method = request.getMethod();
		String type = request.getContentType() == null ? "" : request.getContentType().toLowerCase(Locale.ROOT);
		boolean readable = ("POST".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method))
			&& !type.startsWith("multipart/");
		if (!readable) {
			return null;
		}
		byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
		return body.length > maxBody ? null : body;
	}

	/**
	 * Quem repetiu a recusa: a sessão quando ela vale (a mesma conta em outro aparelho é a mesma
	 * conta) ou, sem sessão, método + rota + impressão do cliente e do corpo. O segredo do servidor
	 * evita que a impressão seja calculada de fora.
	 */
	private String identityOf(HttpServletRequest request, byte[] body, String userId) {
		if (!userId.isBlank()) {
			return "user:" + userId;
		}
		String payload = request.getMethod() + "\n" + routeOf(RequestPath.applicationPath(request)) + "\n"
			+ (request.getHeader("X-Device-Fingerprint") == null ? "" : request.getHeader("X-Device-Fingerprint"))
			+ "\n" + ipResolver.resolveNetwork(request) + "\n"
			+ (body == null ? "-" : Hashes.sha256Hex(new String(body, StandardCharsets.UTF_8)));
		return "anon:" + Hashes.sha256Hex(secret + "\n" + payload);
	}

	static String routeOf(String path) {
		String[] parts = path.split("/", -1);
		for (int i = 0; i < parts.length; i++) {
			parts[i] = VARIABLE_SEGMENT.matcher(parts[i]).replaceAll("{id}");
		}
		return String.join("/", parts);
	}

	/** Regra administrativa que o caminho aciona — o mesmo recorte do interceptor. */
	static String ruleOf(String path) {
		if (path.startsWith("/admin/support/github")) {
			return "ADMIN";
		}
		if (path.matches("/admin/(appeals|support|suggestions|emails|blocked-ips|monitoring)/.*")
				|| path.matches("/admin/users/[^/]+/(sanction|sanitize|ips)")) {
			return "MODERATOR";
		}
		if (path.startsWith("/admin/")) {
			return "ADMIN";
		}
		return "MODERATOR";
	}

	/** Segmento com cara de id (uuid, id com prefixo, número longo): a rota não vira uma por alvo. */
	private static final Pattern VARIABLE_SEGMENT = Pattern.compile(
		"^(?=.{12,}$)(?=.*[0-9])[A-Za-z0-9_-]+$");
}