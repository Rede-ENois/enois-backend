package com.socialcoisa.rede.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository;

/**
 * O evento que falha para sempre tem de PARAR de ser reprocessado.
 *
 * <p>Antes, o limite de tentativas existia só para escrever um log: o evento continuava
 * PENDING, voltava na fila depois do backoff e era tentado de novo, para sempre. Como o lote sai
 * sempre pelos 50 mais antigos, um evento assim não só gasta CPU: ele ocupa a vaga do lote e
 * empurra os eventos novos para fora da leitura — a fila anda na velocidade do evento
 * envenenado.</p>
 *
 * <p>Este teste trava o comportamento: depois do limite, o evento sai de PENDING para um estado
 * terminal (FAILED), o erro fica guardado, e o lote seguinte traz os outros eventos.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
		"rede.outbox.worker-delay-ms=3600000",
		"rede.outbox.claim-stale-seconds=60" })
class OutboxMaxAttemptsTests {

	@TestConfiguration
	static class TestHandlersConfig {

		@Bean
		AlwaysFailingHandler alwaysFailingHandler() {
			return new AlwaysFailingHandler();
		}
	}

	/** Falha sempre: e o que um handler quebrado faz com o evento que recebe. */
	static class AlwaysFailingHandler implements OutboxEventHandler {

		@Override
		public void handle(String eventType, String aggregateId, String payload) {
			throw new IllegalStateException("Falha permanente proposital " + aggregateId);
		}
	}

	@Autowired private OutboxWorker worker;
	@Autowired private OutboxEventRepository outbox;
	@Autowired private JdbcTemplate jdbc;

	private final String prefix = "outbox_max_" + UUID.randomUUID().toString().substring(0, 8);

	/**
	 * O limite entra por campo, nao por property: uma property a mais no {@code @TestPropertySource}
	 * cria outro contexto do Spring (fork de ~3s e pool proprio) e desloca o timing das outras
	 * classes da suite. Este teste precisa de limite 3 e nao de outro contexto.
	 */
	@BeforeEach
	void limiteDeTresTentativas() {
		ReflectionTestUtils.setField(worker, "maxAttempts", 3);
	}

	@Test
	void eventoQueFalhaDemaisVaiParaEstadoTerminalELiberaOLote() {
		String envenenado = append("TesteSempreFalha");
		String vizinho = append("TesteSempreFalha");

		// O limite e 3: as tres primeiras tentativas falham e a quarta leva o evento para FAILED.
		for (int tentativa = 0; tentativa < 4; tentativa++) {
			makeDueNow(envenenado);
			worker.processOne(envenenado);
		}

		assertThat(status(envenenado)).as("o evento envenenado sai de PENDING").isEqualTo("FAILED");
		assertThat(attempts(envenenado)).as("as tentativas ficam contadas").isEqualTo(3);
		assertThat(lastError(envenenado)).as("o erro fica guardado para o diagnostico").isNotBlank();

		// E o lote volta a andar: o vizinho percorre as proprias tentativas e tambem fecha.
		for (int tentativa = 0; tentativa < 4; tentativa++) {
			makeDueNow(vizinho);
			worker.processOne(vizinho);
		}
		assertThat(status(vizinho)).as("o vizinho nao ficou preso atras do envenenado").isEqualTo("FAILED");
	}

	@Test
	void eventoTerminalNaoVoltaParaAFila() {
		String envenenado = append("TesteSempreFalha");
		for (int tentativa = 0; tentativa < 4; tentativa++) {
			makeDueNow(envenenado);
			worker.processOne(envenenado);
		}
		assertThat(status(envenenado)).isEqualTo("FAILED");

		// A proxima rodada nao pega mais: o evento terminal nao e PENDING.
		makeDueNow(envenenado);
		worker.processOne(envenenado);

		assertThat(status(envenenado)).as("FAILED e terminal, nao volta a PENDING").isEqualTo("FAILED");
		assertThat(attempts(envenenado)).as("nao conta tentativa nova").isEqualTo(3);
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
}
