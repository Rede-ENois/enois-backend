package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * Recusas de autorização em {@code /admin/**} e {@code /moderation/**} deixam de ser invisíveis.
 *
 * <p>Antes, a resposta 403 ia embora sem deixar rastro: era possível tentar rota administrativa em
 * série e nada, além de um acesso negado no log de aplicação, contava isso. Aqui cada recusa vira
 * uma linha na MESMA trilha de auditoria das ações administrativas, com o motivo
 * ({@value #ADMIN_ONLY}, {@value #MODERATOR_ONLY}, {@value #SESSION_MISSING}) e o IP já resolvido —
 * e, em memória por pod, um contador por conta alimenta o painel de monitoramento.</p>
 *
 * <p>Nada de corpo de requisição, cabeçalho de sessão ou qualquer dado pessoal: a linha guarda o
 * método, a rota, o status, o motivo e o IP (o mesmo que o filtro de observabilidade já registra).
 * Uma conta passa de {@code rede.security.denied-admin-alerts.threshold} recusas na janela → um
 * aviso sai UMA vez por conta e por janela, para o painel não repetir o mesmo alerta a cada
 * tentativa.</p>
 *
 * <p>ponytail: contador em memória por pod; a trilha durável é a linha de auditoria.</p>
 */
@Component
public class AdminAuthorityAudit {

	private static final Logger LOG = LoggerFactory.getLogger(AdminAuthorityAudit.class);

	/** Ação da linha de auditoria; pública porque o teste de integração e o painel a citam. */
	public static final String ACTION = "ADMIN_AUTHORITY_DENIED";
	/** Motivos: a rota exige administrador. */
	public static final String ADMIN_ONLY = "ADMIN_ONLY";
	/** Motivos: a rota exige moderador (ou administrador). */
	public static final String MODERATOR_ONLY = "MODERATOR_ONLY";
	/** Motivos: sem sessão válida — o 401 do interceptor. */
	public static final String SESSION_MISSING = "SESSION_MISSING";
	/** Motivos: sessao limitada a consulta da decisao e ao recurso. */
	public static final String SESSION_RESTRICTED = "SESSION_RESTRICTED";

	private static final int MAX_TRACKED_USERS = 5_000;
	private static final long MINUTE = 60_000L;

	private final AuditTrail audit;
	private final Clock clock;
	private final int alertThreshold;
	private final long alertWindowMs;
	private final Map<String, Denials> denials = new ConcurrentHashMap<>();
	private final AtomicLong total = new AtomicLong();
	private final AtomicLong alerts = new AtomicLong();
	private final AtomicLong lastDenialAt = new AtomicLong();

	public AdminAuthorityAudit(AuditTrail audit, Clock clock,
			@Value("${rede.security.denied-admin-alerts.threshold:20}") int alertThreshold,
			@Value("${rede.security.denied-admin-alerts.window-minutes:10}") long alertWindowMinutes) {
		this.audit = audit;
		this.clock = clock;
		this.alertThreshold = Math.max(1, alertThreshold);
		this.alertWindowMs = Math.max(1, alertWindowMinutes) * MINUTE;
	}

	/**
	 * Registra uma recusa. {@code userId} nulo é a chamada sem sessão: a linha continua existindo
	 * (o ator é anônimo) e entra na contagem do IP, não na de uma conta.
	 */
	public void denied(String userId, String method, String path, int status, String reason, String clientIp) {
		long now = clock.millis();
		String subject = StringUtils.hasText(userId) ? userId.trim() : "";
		String route = routeOf(path);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("method", method == null ? "" : method);
		metadata.put("route", route);
		metadata.put("status", status);
		metadata.put("reason", reason);
		metadata.put("clientIp", clientIp == null ? "" : clientIp);
		record(subject, route, metadata);
		total.incrementAndGet();
		lastDenialAt.set(now);
		register(subject, method, route, reason, now);
	}

	/**
	 * Grava a recusa na trilha comum de auditoria. A linha de base usa este mesmo caminho quando a
	 * repetição anônima passa do limiar, para a ação e o formato não divergirem entre os dois
	 * chamadores.
	 */
	public void record(String userId, String rule, Map<String, ?> metadata) {
		String actor = StringUtils.hasText(userId) ? userId.trim() : "anonymous";
		try {
			audit.record(actor, ACTION, rule, metadataJson(metadata));
		}
		catch (RuntimeException failure) {
			// Auditoria nunca derruba a resposta: a recusa já aconteceu e o cliente recebe o 403/401
			// de qualquer jeito. A falha fica no log para o operador ver a trilha incompleta.
			LOG.warn("administrative_denial_audit_failed rule={}", rule);
		}
	}

	/**
	 * Quantas contas passaram do limiar na janela. A consulta é para o painel: o mapa é pequeno
	 * (contas que tentaram rota administrativa), e a lista sai ordenada da mais insistente para a
	 * menos.
	 */
	public List<SuspectView> suspects() {
		long now = clock.millis();
		List<SuspectView> result = new ArrayList<>();
		for (Map.Entry<String, Denials> entry : denials.entrySet()) {
			Denials value = entry.getValue();
			synchronized (value) {
				int inWindow = value.window(now, alertWindowMs).size();
				if (inWindow >= alertThreshold) {
					result.add(new SuspectView(entry.getKey(), inWindow, value.total, value.head));
				}
			}
		}
		result.sort(Comparator.comparingInt(SuspectView::denialsInWindow).reversed());
		return List.copyOf(result);
	}

	public Summary summary() {
		long last = lastDenialAt.get();
		return new Summary(total.get(), alerts.get(), suspects().size(),
			last == 0 ? null : Instant.ofEpochMilli(last).toString());
	}

	private void register(String subject, String method, String route, String reason, long now) {
		if (!StringUtils.hasText(subject)) {
			// Chamada sem sessão: não há conta a alertar, e o IP já está na trilha.
			return;
		}
		if (denials.size() >= MAX_TRACKED_USERS && !denials.containsKey(subject)) {
			// Teto de memória: o ataque de ids distintos não pode crescer o mapa sem limite. A
			// linha de auditoria continua sendo gravada para todos.
			return;
		}
		Denials value = denials.computeIfAbsent(subject, key -> new Denials());
		boolean alert;
		int inWindow;
		synchronized (value) {
			value.head = new Denial(method, route, reason);
			value.stamps.addLast(now);
			value.total++;
			inWindow = value.window(now, alertWindowMs).size();
			// Um aviso por janela: o limiar cruzado uma vez não repete a cada tentativa seguinte.
			alert = inWindow >= alertThreshold && now - value.lastAlertAt >= alertWindowMs;
			if (alert) {
				value.lastAlertAt = now;
			}
		}
		if (alert) {
			alerts.incrementAndGet();
			LOG.warn("denied_admin_authorizations subject={} countInWindow={} windowMinutes={}",
				subject, inWindow, alertWindowMs / MINUTE);
		}
	}

	/** A rota sem id variável: a lista de suspeitos não fica com uma linha por recurso tentado. */
	static String routeOf(String path) {
		if (!StringUtils.hasText(path)) {
			return "/";
		}
		String clean = path.length() > 400 ? path.substring(0, 400) : path;
		String[] parts = clean.split("/", -1);
		for (int i = 0; i < parts.length; i++) {
			if (parts[i].length() >= 16 && parts[i].chars().anyMatch(Character::isDigit)
					&& parts[i].chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_')) {
				parts[i] = "{id}";
			}
		}
		return String.join("/", parts).toLowerCase(Locale.ROOT);
	}

	private static String metadataJson(Map<String, ?> metadata) {
		StringBuilder json = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, ?> entry : metadata.entrySet()) {
			if (!first) {
				json.append(',');
			}
			first = false;
			json.append('"').append(quote(entry.getKey())).append("\":");
			Object value = entry.getValue();
			if (value instanceof Number || value instanceof Boolean) {
				json.append(value);
			}
			else {
				json.append('"').append(quote(value == null ? "" : value.toString())).append('"');
			}
		}
		return json.append('}').toString();
	}

	private static String quote(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/**
	 * O que o painel mostra para uma conta: quantas recusas na janela, quantas no total e a última
	 * tentativa (método, rota e motivo).
	 */
	public record SuspectView(String userId, int denialsInWindow, long totalDenials, Denial lastDenial) {
	}

	/** Uma recusa, na forma que a lista de suspeitos mostra. */
	public record Denial(String method, String route, String reason) {
	}

	public record Summary(long total, long alerts, int suspects, String lastDenialAt) {
	}

	private static final class Denials {

		private final Deque<Long> stamps = new ArrayDeque<>();
		private long total;
		private long lastAlertAt;
		private Denial head = new Denial("", "", "");

		/** As ocorrências ainda dentro da janela; descarta as mais antigas. */
		private List<Long> window(long now, long windowMs) {
			while (!stamps.isEmpty() && now - stamps.peekFirst() > windowMs) {
				stamps.pollFirst();
			}
			return new ArrayList<>(stamps);
		}
	}
}
