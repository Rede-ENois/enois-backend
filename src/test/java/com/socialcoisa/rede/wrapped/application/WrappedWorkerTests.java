package com.socialcoisa.rede.wrapped.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import com.socialcoisa.rede.wrapped.domain.WrappedAno;

/**
 * A janela do worker é o que protege o horário normal da plataforma: nenhuma conta é processada
 * fora de 00:00–05:00 (fuso da aplicação), e as fronteiras são fechadas à esquerda e abertas à
 * direita — 00:00 entra, 05:00 não. O "entre 26/12 e 01/01" também é medido no fuso da aplicação.
 */
class WrappedWorkerTests {

	private static final int ANO = 2026;

	private WrappedWorker worker(Instant agora) {
		return new WrappedWorker(null, null, Clock.fixed(agora, ZoneId.of("America/Sao_Paulo")), ANO,
				true, false);
	}

	private static Instant saoPaulo(int mes, int dia, int hora, int minuto) {
		return java.time.LocalDateTime.of(2026, mes, dia, hora, minuto).atZone(WrappedAno.FUSO).toInstant();
	}

	@Test
	void janelaFechaAsCincoDaManha() {
		assertThat(worker(saoPaulo(12, 26, 0, 0)).dentroDaJanelaDeProcessamento(saoPaulo(12, 26, 0, 0))).isTrue();
		assertThat(worker(saoPaulo(12, 26, 4, 59)).dentroDaJanelaDeProcessamento(saoPaulo(12, 26, 4, 59))).isTrue();
		assertThat(worker(saoPaulo(12, 26, 5, 0)).dentroDaJanelaDeProcessamento(saoPaulo(12, 26, 5, 0))).isFalse();
		assertThat(worker(saoPaulo(12, 26, 3, 0)).dentroDaJanelaDeProcessamento(saoPaulo(12, 26, 12, 0))).isFalse();
	}

	@Test
	void periodoDeExibicaoVaiDe26DeDezembroA1DeJaneiro() {
		assertThat(worker(saoPaulo(12, 25, 23, 59)).dentroDoPeriodoDeExibicao(saoPaulo(12, 25, 23, 59))).isFalse();
		assertThat(worker(saoPaulo(12, 26, 0, 1)).dentroDoPeriodoDeExibicao(saoPaulo(12, 26, 0, 1))).isTrue();
		assertThat(worker(saoPaulo(12, 31, 23, 59)).dentroDoPeriodoDeExibicao(saoPaulo(12, 31, 23, 59))).isTrue();
		assertThat(worker(saoPaulo(1, 1, 0, 0)).dentroDoPeriodoDeExibicao(saoPaulo(1, 1, 0, 0))).isFalse();
	}

	@Test
	void fronteiraDeAnoRespeitaAFusoDaAplicacao() {
		// 00:00 em São Paulo é 03:00 UTC — a janela abre nesse instante, não à meia-noite UTC.
		Instant meiaNoiteUtc = Instant.parse("2026-12-26T00:00:00Z");
		assertThat(worker(meiaNoiteUtc).dentroDoPeriodoDeExibicao(meiaNoiteUtc)).isFalse();
		Instant meiaNoiteLocal = Instant.parse("2026-12-26T03:00:00Z");
		assertThat(worker(meiaNoiteLocal).dentroDoPeriodoDeExibicao(meiaNoiteLocal)).isTrue();
	}
}
