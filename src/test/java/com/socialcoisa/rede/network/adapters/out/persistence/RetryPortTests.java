package com.socialcoisa.rede.network.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.network.domain.TaskLatency;

/**
 * O contrato da porta de retentativa, isolado: é aqui que a diferença "contagem × duração" se
 * decide, e o teste pela rota não a distingue (os valores válidos de tentativa perdida, 0 e 1,
 * passam pelo {@code bucketOf} sem mudar de valor).
 *
 * <p>A escrita recebe a contagem e tem de gravá-la como está. Com {@code bucketOf}, 3 tentativas
 * perdidas viravam a faixa 4 — e o percentil responderia "quatro tentativas" para quem fez quatro.</p>
 */
class RetryPortTests {

	@Test
	void aPortaRecebeAContagemDeTentativasPerdidas() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		NodeObservabilityPersistenceAdapter adapter = new NodeObservabilityPersistenceAdapter(jdbc);

		adapter.addRetrySample("nd_teste", Instant.parse("2026-11-28T10:00:00Z"), 3L);

		verify(jdbc).update(anyString(), eq("nd_teste"), any(), eq(3));
	}

	@Test
	void aContagemNaoViraFaixaDeTempo() {
		// A prova de que a diferença é real: 3 e 4 são o mesmo em tentativas e faixas diferentes em ms.
		assertThat(TaskLatency.bucketOf(3)).as("a faixa de 3 ms é 4").isEqualTo(4);
		TaskLatency tres = new TaskLatency(java.util.Map.of(), java.util.Map.of(3, 1));
		assertThat(tres.retriesP50()).as("3 tentativas perdidas respondem 3, não 4").isEqualTo(3);
	}
}