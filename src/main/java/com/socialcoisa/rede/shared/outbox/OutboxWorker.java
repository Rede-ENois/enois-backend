package com.socialcoisa.rede.shared.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consome a outbox. Um evento so vira PROCESSED quando todos os handlers concluiram; a falha nao
 * some, fica registrada no evento com backoff para a proxima tentativa.
 *
 * <p>Garantias, uma por desenho:</p>
 * <ul>
 * <li><b>Sem perder efeito:</b> excecao de handler nao e engolida. O evento continua PENDING, o
 * numero de falhas sobe e a proxima tentativa fica agendada.</li>
 * <li><b>Sem repetir efeito de banco:</b> cada handler grava o seu recibo ({@code
 * processed_event_handlers}, par evento + handler) na MESMA transacao dos efeitos que produziu.
 * Recibo e efeito commitam juntos, entao a nova tentativa pula o handler que ja concluiu e executa
 * so o que faltou.</li>
 * <li><b>Entre instancias:</b> o evento e reservado por claim atomico antes de rodar; quem nao
 * recebe o claim pula. A reserva de uma instancia que caiu expira e o evento volta a ser elegivel.</li>
 * <li><b>Isolamento:</b> cada handler roda na propria transacao e cada evento e uma rodada propria:
 * um evento que falhou nao desfaz nem repete os vizinhos do lote.</li>
 * </ul>
 *
 * <p>O que segue "pelo menos uma vez": efeito externo a transacao (o share no LinkedIn). Ele esta
 * protegido pelo recibo do handler no caminho normal; uma queda entre o envio e o commit ainda
 * repete o envio, por isso a integracao precisa ser idempotente do lado dela.</p>
 */
@Component
class OutboxWorker {

	private static final Logger LOG = LoggerFactory.getLogger(OutboxWorker.class);

	private static final int BATCH_SIZE = 50;
	private static final int ERROR_LENGTH = 500;
	private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

	private final SpringDataOutboxEventJpaRepository outboxRepository;
	private final SpringDataOutboxHandlerReceiptJpaRepository receiptRepository;
	private final List<OutboxEventHandler> handlers;
	private final Clock clock;
	private final TransactionTemplate transaction;
	private final Duration claimStaleAfter;
	private final int maxAttempts;
	private final OutboxMetrics metrics;

	OutboxWorker(SpringDataOutboxEventJpaRepository outboxRepository,
			SpringDataOutboxHandlerReceiptJpaRepository receiptRepository, List<OutboxEventHandler> handlers,
			Clock clock, PlatformTransactionManager transactionManager, OutboxMetrics metrics,
			@Value("${rede.outbox.claim-stale-seconds:60}") long claimStaleSeconds,
			@Value("${rede.outbox.max-attempts:20}") int maxAttempts) {
		this.outboxRepository = outboxRepository;
		this.receiptRepository = receiptRepository;
		this.handlers = handlers;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
		this.claimStaleAfter = Duration.ofSeconds(claimStaleSeconds);
		this.maxAttempts = maxAttempts;
		this.metrics = metrics;
	}

	// O initialDelay e 0 em producao (primeira passada no boot). Os testes que dirigem o worker na
	// mao o afastam, senao essa primeira passada roda junto com o teste e rouba o claim dele.
	@Scheduled(initialDelayString = "${rede.outbox.worker-initial-delay-ms:0}",
			fixedDelayString = "${rede.outbox.worker-delay-ms:5000}")
	void processPendingEvents() {
		List<OutboxEventJpaEntity> pending = outboxRepository
			.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("PENDING", clock.instant(),
				PageRequest.ofSize(BATCH_SIZE));
		for (OutboxEventJpaEntity event : pending) {
			processOne(event.id());
		}
	}

	/** Quantos handlers escutam cada evento (uso de teste e diagnostico). */
	int handlerCount() {
		return handlers.size();
	}

	/**
	 * Reserva e executa um evento, sem passar pelo relogio do agendador.
	 *
	 * @return false quando outra rodada ja reservou o evento, isto e, quando nada foi executado aqui
	 */
	boolean processOne(String eventId) {
		if (!reserve(eventId)) {
			return false;
		}
		try {
			for (OutboxEventHandler handler : handlers) {
				runHandler(eventId, handler);
			}
			markProcessed(eventId);
			return true;
		}
		catch (RuntimeException exception) {
			LOG.error("Evento {} falhou e voltou para a fila", eventId, exception);
			recordFailure(eventId, exception);
			return true;
		}
	}

	private boolean reserve(String eventId) {
		Instant now = clock.instant();
		return Boolean.TRUE.equals(transaction.execute(
			status -> outboxRepository.claim(eventId, "PENDING", now, now.minus(claimStaleAfter)) == 1));
	}

	/** Recibo e efeito do handler na mesma transacao: ou os dois valem, ou nenhum vale. */
	private void runHandler(String eventId, OutboxEventHandler handler) {
		String name = handler.getClass().getName();
		transaction.executeWithoutResult(status -> {
			OutboxEventJpaEntity event = pendingEvent(eventId);
			if (event == null) {
				return;
			}
			if (receiptRepository.insertIfAbsent(eventId, name, clock.instant()) == 0) {
				return;
			}
			handler.handle(event.eventType(), event.aggregateId(), event.payload());
		});
	}

	private void markProcessed(String eventId) {
		transaction.executeWithoutResult(status -> {
			OutboxEventJpaEntity event = pendingEvent(eventId);
			if (event == null) {
				return;
			}
			event.markProcessed();
			outboxRepository.save(event);
		});
	}

	/** Null quando o evento saiu de PENDING por outra rodada: nao ha nada a executar nem a marcar. */
	private OutboxEventJpaEntity pendingEvent(String eventId) {
		return outboxRepository.findById(eventId)
			.filter(event -> "PENDING".equals(event.status()))
			.orElse(null);
	}

	private void recordFailure(String eventId, RuntimeException exception) {
		Instant now = clock.instant();
		String error = describe(exception);
		try {
			transaction.executeWithoutResult(status -> {
				OutboxEventJpaEntity event = pendingEvent(eventId);
				if (event == null) {
					return;
				}
				// O limite fecha a fila: o evento envenenado sai de PENDING e para de ser
				// escolhido. Antes ele voltava para sempre, gastando CPU e empurrando os eventos
				// novos para fora do lote de 50 mais antigos.
				if (event.attempts() + 1 >= maxAttempts) {
					event.markFailedTerminal(error);
					outboxRepository.save(event);
					LOG.error("Evento {} ({}) esgotou {} tentativas e virou FAILED; o efeito NAO foi "
						+ "aplicado e precisara de intervencao manual: {}", event.id(), event.eventType(),
						maxAttempts, error);
					metrics.increaseFailedTerminal();
					return;
				}
				event.markFailed(error, now.plus(backoff(event.attempts() + 1)));
				outboxRepository.save(event);
			});
		}
		catch (RuntimeException secondFailure) {
			// Sem registrar a falha o claim envelhece e expira; o evento volta sozinho.
			LOG.error("Nao foi possivel registrar a falha do evento {}", eventId, secondFailure);
		}
	}

	/** 10s, 20s, 30s... ate 5 min: nova tentativa rapida para falha passageira, sem martelar. */
	static Duration backoff(int attempts) {
		long seconds = Math.min(MAX_BACKOFF.toSeconds(), Math.max(10L, attempts * 10L));
		return Duration.ofSeconds(seconds);
	}

	private static String describe(RuntimeException exception) {
		String message = exception.getMessage();
		String text = exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
		return text.length() > ERROR_LENGTH ? text.substring(0, ERROR_LENGTH) : text;
	}
}
