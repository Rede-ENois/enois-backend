package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O scheduler como handler do outbox (ADR-004): o evento pede a tarefa, e o mesmo evento
 * reprocessado não cria uma segunda.
 *
 * <p>O outbox grava o recibo do handler antes de chamá-lo, então um evento pode chegar duas vezes
 * ao handler em cenários de retomada. A chave de idempotência derivada do agregado é o que impede
 * que isso vire trabalho duplicado — e é isso que este teste fixa.</p>
 */
@SpringBootTest
class NetworkTaskOutboxHandlerTests {

	@Autowired
	private NetworkTaskOutboxEventHandler handler;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from network_tasks");
		jdbc.update("delete from network_state");
	}

	@Test
	void eventoDeTarefaCriaATarefaNaFila() {
		handler.handle(NetworkTaskOutboxEventHandler.EVENT_TASK_REQUESTED, "post_123",
			"{\"type\":\"ASYNC_JOB\",\"dataClass\":\"PUBLIC\"}");

		assertThat(jdbc.queryForObject("select count(*) from network_tasks", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select task_type from network_tasks", String.class)).isEqualTo("ASYNC_JOB");
		assertThat(jdbc.queryForObject("select idempotency_key from network_tasks", String.class))
			.isEqualTo("evt:post_123");
	}

	@Test
	void mesmoEventoReprocessadoNaoCriaSegundaTarefa() {
		String payload = "{\"type\":\"ASYNC_JOB\",\"dataClass\":\"PUBLIC\"}";
		handler.handle(NetworkTaskOutboxEventHandler.EVENT_TASK_REQUESTED, "post_456", payload);
		handler.handle(NetworkTaskOutboxEventHandler.EVENT_TASK_REQUESTED, "post_456", payload);

		assertThat(jdbc.queryForObject("select count(*) from network_tasks", Integer.class)).isEqualTo(1);
	}

	@Test
	void eventoComTipoDesconhecidoNaoCriaTarefaENaoEstoura() {
		// Evento malformado não pode virar retentativa infinita do outbox: registra e segue.
		handler.handle(NetworkTaskOutboxEventHandler.EVENT_TASK_REQUESTED, "post_789", "{\"type\":\"NAO_EXISTE\"}");

		assertThat(jdbc.queryForObject("select count(*) from network_tasks", Integer.class)).isZero();
	}

	@Test
	void classeDeDadoDesconhecidaCaiNaMaisRestritiva() {
		handler.handle(NetworkTaskOutboxEventHandler.EVENT_TASK_REQUESTED, "post_abc",
			"{\"type\":\"ASYNC_JOB\",\"dataClass\":\"ALGO_NOVO\"}");

		// Erra para o lado de não vazar: classe desconhecida é tratada como dado pessoal.
		assertThat(jdbc.queryForObject("select data_class from network_tasks", String.class)).isEqualTo("PERSONAL");
	}

	@Test
	void eventoDeOutroTipoNaoCriaTarefa() {
		handler.handle("PostPublished", "post_def", "{}");

		assertThat(jdbc.queryForObject("select count(*) from network_tasks", Integer.class)).isZero();
	}
}
