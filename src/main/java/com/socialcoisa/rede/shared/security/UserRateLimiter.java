package com.socialcoisa.rede.shared.security;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Limite de ações por usuário autenticado — complementa o {@link IpRateLimiter} (que atua por IP).
 *
 * O IpRateLimiter protege autenticação (signup, login). Este protege ações dentro do app: publicar
 * posts, comentar, candidatar-se a vagas e enviar mensagens. A mecânica é a mesma (janela
 * deslizante em memória, sem Redis), e a mesma consequência se aplica: o limite vale por pod.
 *
 * <p>Fase 3: conta rejeicoes por usuario. 3+ rejeicoes/hora publica {@link AutoReportEvent}
 * para a moderacao.
 */
@Component
public class UserRateLimiter {

	private static final Logger LOG = LoggerFactory.getLogger(UserRateLimiter.class);

	public static final String CREATE_POST = "create_post";
	public static final String CREATE_COMMENT = "create_comment";
	public static final String APPLY_JOB = "apply_job";
	/** Candidatura a vaga EXTERNA: dispara e-mail real para uma caixa de terceiro. */
	public static final String EXTERNAL_APPLY = "external_apply";
	public static final String SEND_MESSAGE = "send_message";
	public static final String SYNC_GITHUB = "sync_github";
	public static final String START_CALL = "start_call";

	private static final int MAX_KEYS = 50_000;
	static final int REJECTION_THRESHOLD = 3;
	private static final long REJECTION_WINDOW_MS = 3_600_000L;

	private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
	private final Map<String, Deque<Long>> rejections = new ConcurrentHashMap<>();
	private final Clock clock;
	private final ApplicationEventPublisher events;

	private final int postLimit;
	private final long postWindowMs;
	private final int commentLimit;
	private final long commentWindowMs;
	private final int jobApplyLimit;
	private final long jobApplyWindowMs;
	private final int externalApplyLimit;
	private final long externalApplyWindowMs;
	private final int messageLimit;
	private final long messageWindowMs;
	private final int githubSyncLimit;
	private final long githubSyncWindowMs;
	private final int callStartLimit;
	private final long callStartWindowMs;

	public UserRateLimiter(Clock clock, ApplicationEventPublisher events,
			@Value("${rede.rate.post-limit:10}") int postLimit,
			@Value("${rede.rate.post-window-minutes:60}") long postWindowMinutes,
			@Value("${rede.rate.comment-limit:30}") int commentLimit,
			@Value("${rede.rate.comment-window-minutes:60}") long commentWindowMinutes,
			@Value("${rede.rate.job-apply-limit:100}") int jobApplyLimit,
			@Value("${rede.rate.job-apply-window-minutes:30}") long jobApplyWindowMinutes,
			@Value("${rede.rate.external-apply-limit:10}") int externalApplyLimit,
			@Value("${rede.rate.external-apply-window-minutes:60}") long externalApplyWindowMinutes,
			@Value("${rede.rate.message-limit:60}") int messageLimit,
			@Value("${rede.rate.message-window-minutes:1}") long messageWindowMinutes,
			@Value("${rede.rate.github-sync-limit:3}") int githubSyncLimit,
			@Value("${rede.rate.github-sync-window-minutes:60}") long githubSyncWindowMinutes,
			@Value("${rede.rate.call-start-limit:20}") int callStartLimit,
			@Value("${rede.rate.call-start-window-minutes:60}") long callStartWindowMinutes) {
		this.clock = clock;
		this.events = events;
		this.postLimit = postLimit;
		this.postWindowMs = postWindowMinutes * 60_000L;
		this.commentLimit = commentLimit;
		this.commentWindowMs = commentWindowMinutes * 60_000L;
		this.jobApplyLimit = jobApplyLimit;
		this.jobApplyWindowMs = jobApplyWindowMinutes * 60_000L;
		this.externalApplyLimit = externalApplyLimit;
		this.externalApplyWindowMs = externalApplyWindowMinutes * 60_000L;
		this.messageLimit = messageLimit;
		this.messageWindowMs = messageWindowMinutes * 60_000L;
		this.githubSyncLimit = githubSyncLimit;
		this.githubSyncWindowMs = githubSyncWindowMinutes * 60_000L;
		this.callStartLimit = callStartLimit;
		this.callStartWindowMs = callStartWindowMinutes * 60_000L;
	}

	/**
	 * Verifica se o usuário pode executar a ação. Lança 429 se o limite foi atingido.
	 * Quando o limite e rejeitado, conta a rejeicao e, ao atingir o limiar, reporta.
	 */
	public void require(String userId, String action) {
		if (!tryAcquire(userId, action)) {
			trackRejection(userId, action);
			throw new ApiException(429, "RATE_LIMIT_EXCEEDED",
				"Voce atingiu o limite desta acao. Tente novamente em alguns minutos.");
		}
	}

	boolean tryAcquire(String userId, String action) {
		long now = clock.millis();
		long window = windowFor(action);
		int limit = limitFor(action);
		if (limit <= 0 || window <= 0) {
			return true;
		}
		if (hits.size() > MAX_KEYS) {
			sweep(now);
		}

		Deque<Long> timestamps = hits.computeIfAbsent(action + "|" + userId, key -> new ArrayDeque<>());
		synchronized (timestamps) {
			while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= window) {
				timestamps.pollFirst();
			}
			if (timestamps.size() >= limit) {
				return false;
			}
			timestamps.addLast(now);
			return true;
		}
	}

	private void trackRejection(String userId, String action) {
		long now = clock.millis();
		Deque<Long> ts = rejections.computeIfAbsent(userId, k -> new ArrayDeque<>());
		int count;
		synchronized (ts) {
			while (!ts.isEmpty() && now - ts.peekFirst() >= REJECTION_WINDOW_MS) {
				ts.pollFirst();
			}
			ts.addLast(now);
			count = ts.size();
		}
		if (count == REJECTION_THRESHOLD) {
			LOG.warn("Usuario {} atingiu {} rejeicoes de rate limit em 1h (acao: {})", userId, count, action);
			try {
				events.publishEvent(new AutoReportEvent(userId, TargetType.USER, ReportReason.SPAM,
					"[auto] Usuario rejeitado pelo rate limiter " + count
						+ " vezes em 1 hora (ultima acao: " + action + ")."));
			} catch (RuntimeException e) {
				LOG.debug("Falha ao publicar auto-report para {}: {}", userId, e.getMessage());
			}
		}
	}

	private long windowFor(String action) {
		return switch (action) {
			case CREATE_POST -> postWindowMs;
			case CREATE_COMMENT -> commentWindowMs;
			case APPLY_JOB -> jobApplyWindowMs;
			case EXTERNAL_APPLY -> externalApplyWindowMs;
			case SEND_MESSAGE -> messageWindowMs;
			case SYNC_GITHUB -> githubSyncWindowMs;
			case START_CALL -> callStartWindowMs;
			default -> 0L;
		};
	}

	private int limitFor(String action) {
		return switch (action) {
			case CREATE_POST -> postLimit;
			case CREATE_COMMENT -> commentLimit;
			case APPLY_JOB -> jobApplyLimit;
			case EXTERNAL_APPLY -> externalApplyLimit;
			case SEND_MESSAGE -> messageLimit;
			case SYNC_GITHUB -> githubSyncLimit;
			case START_CALL -> callStartLimit;
			default -> 0;
		};
	}

	private void sweep(long now) {
		hits.entrySet().removeIf(entry -> {
			Deque<Long> timestamps = entry.getValue();
			synchronized (timestamps) {
				String action = entry.getKey().substring(0, entry.getKey().indexOf('|'));
				long window = windowFor(action);
				while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= window) {
					timestamps.pollFirst();
				}
				return timestamps.isEmpty();
			}
		});
	}
}
