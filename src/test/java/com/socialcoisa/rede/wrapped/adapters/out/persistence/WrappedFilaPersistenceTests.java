package com.socialcoisa.rede.wrapped.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.socialcoisa.rede.wrapped.application.port.out.WrappedFilaRepository;
import com.socialcoisa.rede.wrapped.domain.WrappedEstado;

/**
 * A fila no banco: inserção, leitura em ordem de prioridade, reserva atômica e retomada. Sem HTTP —
 * só o adaptador contra o H2 de teste (mesma migração da produção).
 */
@SpringBootTest
class WrappedFilaPersistenceTests {

	private static final int ANO = 2026;
	private static final Instant AGORA = Instant.parse("2026-12-27T03:00:00Z");

	@Autowired
	private WrappedFilaRepository fila;

	@Test
	void insereLeEmOrdemEReserva() {
		String alta = "user_fila_alta_" + System.nanoTime();
		String baixa = "user_fila_baixa_" + System.nanoTime();
		fila.inserirPendente(alta, ANO, 0, 900, 1, AGORA);
		fila.inserirPendente(baixa, ANO, 1, 10, 1, AGORA);

		var proximas = fila.proximas(ANO, 100);
		assertThat(proximas).contains(alta, baixa);
		assertThat(proximas.indexOf(alta)).isLessThan(proximas.indexOf(baixa));

		assertThat(fila.reservar(alta, ANO, AGORA, AGORA.plusSeconds(600))).isTrue();
		assertThat(fila.contar(ANO, WrappedEstado.PROCESSING)).isGreaterThanOrEqualTo(1);
	}

	@Test
	void reservaExpiradaVoltaParaFila() {
		String user = "user_fila_expirada_" + System.nanoTime();
		fila.inserirPendente(user, ANO, 5, 0, 1, AGORA);
		assertThat(fila.reservar(user, ANO, AGORA, AGORA.minusSeconds(60))).isTrue();
		assertThat(fila.expirarReservas(ANO, AGORA)).isGreaterThanOrEqualTo(1);
		assertThat(fila.buscar(user, ANO).orElseThrow().estado()).isEqualTo(WrappedEstado.PENDING);
	}

	@Test
	void concluirGravaResultado() {
		String user = "user_fila_conclui_" + System.nanoTime();
		fila.inserirPendente(user, ANO, 7, 0, 1, AGORA);
		fila.reservar(user, ANO, AGORA, AGORA.plusSeconds(600));
		fila.concluir(user, ANO, "{\"ano\":2026}", false, AGORA);
		var registro = fila.buscar(user, ANO).orElseThrow();
		assertThat(registro.estado()).isEqualTo(WrappedEstado.DONE);
		assertThat(registro.resultadoJson()).isEqualTo("{\"ano\":2026}");
		assertThat(fila.concluida(user, ANO)).isTrue();
	}
}
