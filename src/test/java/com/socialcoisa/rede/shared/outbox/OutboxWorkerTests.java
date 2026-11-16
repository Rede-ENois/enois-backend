package com.socialcoisa.rede.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository;
import com.socialcoisa.rede.shared.web.TestDatabaseSupport;

/**
 * O contrato do worker da outbox: o evento nao vira PROCESSED com handler que falhou, e a nova
 * tentativa nao repete o efeito que ja concluiu.
 *
 * <p>O handler que falha entra so neste contexto de teste ({@link FailingHandler}) e falha apenas
 * para o tipo de evento que este teste grava: os handlers reais recebem o evento e nao fazem nada,
 * ja que nenhum deles trata esses tipos.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:outbox_worker;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"rede.outbox.worker-initial-delay-ms=3600000",
		"rede.outbox.worker-delay-ms=3600000",
		"rede.outbox.claim-stale-seconds=60" })
class OutboxWorkerTests {

	/** A ponte estática pertence aos contextos com autenticação, não à base isolada deste worker. */
	@MockitoBean private TestDatabaseSupport testDatabaseSupport;

	/** A mesma instancia registrada como bean: o contador tem de ser o do contexto. */
	private final CountingHandler counter = CountingHandler.instance;

	@TestConfiguration
	static class TestHandlersConfig {

		@Bean
		CountingHandler countingHandler() {
			return CountingHandler.instance;
		}

		@Bean
		FailingHandler failingHandler() {
			return new FailingHandler();
		}
	}

	/** Conta quantas vezes um handler foi executado por evento, para provar "uma vez so". */
	static class CountingHandler implements OutboxEventHandler {

		static final CountingHandler instance = new CountingHandler();

		private final Map<String, Integer> perAggregate = new ConcurrentHashMap<>();

		@Override
		public void handle(String eventType, String aggregateId, String payload) {
			if ("TesteContagem".equals(eventType)) {
				perAggregate.merge(aggregateId, 1, Integer::sum);
			}
		}

		void reset() {
			perAggregate.clear();
		}

		int invocations() {
			return perAggregate.values().stream().mapToInt(Integer::intValue).sum();
		}

		int maxPerAggregate() {
			return perAggregate.values().stream().mapToInt(Integer::intValue).max().orElse(0);
		}
	}

	static class FailingHandler implements OutboxEventHandler {

		@Override
		public void handle(String eventType, String aggregateId, String payload) {
			if ("TesteSempreFalha".equals(eventType)) {
				throw new IllegalStateException("TesteSempreFalha: falha proposital " + aggregateId);
			}
		}
	}

	@Autowired private OutboxWorker worker;
	@Autowired private OutboxEventRepository outbox;
	@Autowired private JdbcTemplate jdbc;

	private final String prefix = "outbox_" + UUID.randomUUID().toString().substring(0, 8);

	@Test
	void eventoBemSucedidoViraProcessadoEDeixaReciboDeTodosOsHandlers() {
		String eventId = append("TesteSempreOk");

		assertThat(worker.processOne(eventId)).isTrue();

		assertThat(status(eventId)).isEqualTo("PROCESSED");
		assertThat(receiptCount(eventId)).isEqualTo(worker.handlerCount());
		assertThat(attempts(eventId)).isZero();
	}

	/**
	 * Regressao da correcao: antes, o recibo era gravado ANTES da execucao e o evento virava
	 * PROCESSED mesmo com excecao, perdendo o efeito para sempre.
	 */
	@Test
	void eventoQueFalhaContinuaPendenteComTentativaContadaEProximaData() {
		String eventId = append("TesteSempreFalha");

		assertThat(worker.processOne(eventId)).isTrue();

		assertThat(status(eventId)).isEqualTo("PENDING");
		assertThat(attempts(eventId)).isEqualTo(1);
		assertThat(lastError(eventId)).contains("TesteSempreFalha");
		assertThat(nextAttemptAt(eventId)).isAfter(Instant.now());
	}

	@Test
	void reciboGravadoNaoReexecutaOHandlerNaNovaTentativa() {
		String eventId = append("TesteSempreFalha");
		worker.processOne(eventId);
		int receiptsAfterFirstRound = receiptCount(eventId);

		// a proxima tentativa e forcada aqui: o backoff real nao interessa para o recibo
		makeDueNow(eventId);
		worker.processOne(eventId);

		assertThat(receiptCount(eventId)).isEqualTo(receiptsAfterFirstRound);
		assertThat(attempts(eventId)).isEqualTo(2);
	}

	@Test
	void eventoJaProcessadoNaoRodaDeNovo() {
		String eventId = append("TesteSempreOk");
		worker.processOne(eventId);
		int receipts = receiptCount(eventId);

		assertThat(worker.processOne(eventId)).isFalse();

		assertThat(receiptCount(eventId)).isEqualTo(receipts);
		assertThat(status(eventId)).isEqualTo("PROCESSED");
	}

	/**
	 * Duas rodadas sobrepostas no mesmo evento: o claim deixa uma so executar. A prova nao e a
	 * contagem de claims (a perdedora legitimamente nao faz nada), e sim que, depois de todos os
	 * ciclos terem convergido, cada evento foi executado exatamente uma vez.
	 */
	@Test
	void duasRodadasNaoExecutamOMesmoEventoDuasVezes() throws Exception {
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			ids.add(append("TesteContagem"));
		}
		counter.reset();

		List<Callable<Boolean>> runs = new ArrayList<>();
		for (String id : ids) {
			runs.add(() -> worker.processOne(id));
			runs.add(() -> worker.processOne(id));
		}

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for (Callable<Boolean> run : runs) {
				results.add(pool.submit(() -> {
					start.await(10, TimeUnit.SECONDS);
					return run.call();
				}));
			}
			start.countDown();
			for (Future<Boolean> result : results) {
				result.get(60, TimeUnit.SECONDS);
			}
		}
		finally {
			pool.shutdownNow();
		}

		// convergencia: o que ficou para tras (a rodada perdedora nao executa) sai no proximo ciclo
		for (String id : ids) {
			if ("PENDING".equals(status(id))) {
				worker.processOne(id);
			}
		}

		for (String id : ids) {
			assertThat(status(id)).isEqualTo("PROCESSED");
			assertThat(receiptCount(id)).isEqualTo(worker.handlerCount());
		}
		assertThat(counter.invocations()).isEqualTo(ids.size());
		assertThat(counter.maxPerAggregate()).isEqualTo(1);
	}

	/** Um evento que falha nao impede o vizinho de concluir, nem duplica o dele. */
	@Test
	void eventoComFalhaNaoAfetaOsVizinhosDoLote() {
		String falha = append("TesteSempreFalha");
		String ok = append("TesteSempreOk");

		worker.processOne(falha);
		worker.processOne(ok);

		assertThat(status(falha)).isEqualTo("PENDING");
		assertThat(status(ok)).isEqualTo("PROCESSED");
	}

	private String append(String eventType) {
		String aggregateId = prefix + "_" + eventType + "_" + UUID.randomUUID().toString().substring(0, 8);
		outbox.append(eventType, aggregateId, "{\"test\":\"true\"}");
		return jdbc.queryForObject("select id from outbox_events where aggregate_id = ?", String.class, aggregateId);
	}

	private void makeDueNow(String eventId) {
		jdbc.update("update outbox_events set next_attempt_at = ? where id = ?",
			java.sql.Timestamp.from(Instant.now().minusSeconds(1)), eventId);
	}

	private String status(String eventId) {
		return jdbc.queryForObject("select status from outbox_events where id = ?", String.class, eventId);
	}

	private int attempts(String eventId) {
		return jdbc.queryForObject("select attempts from outbox_events where id = ?", Integer.class, eventId);
	}

	private String lastError(String eventId) {
		return jdbc.queryForObject("select last_error from outbox_events where id = ?", String.class, eventId);
	}

	private Instant nextAttemptAt(String eventId) {
		return jdbc.queryForObject("select next_attempt_at from outbox_events where id = ?", java.sql.Timestamp.class,
			eventId).toInstant();
	}

	private int receiptCount(String eventId) {
		return jdbc.queryForObject("select count(*) from processed_event_handlers where event_id = ?",
			Integer.class, eventId);
	}
}
