package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * Contador da linha de base: o aviso sai no cruzamento do limiar, uma vez por janela, e a conta
 * agregada não cresce sem teto.
 */
class AdminDenialCounterTests {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-11-15T12:00:00Z"));
	private final AdminDenialCounter counter = new AdminDenialCounter(clock, 3, 10);

	@Test
	void alertaSomenteQuandoAContaCruzaOLimiar() {
		assertThat(counter.record("user:1").alert()).isFalse();
		assertThat(counter.record("user:1").alert()).isFalse();

		AdminDenialCounter.Decision third = counter.record("user:1");
		assertThat(third.alert()).isTrue();
		assertThat(third.countInWindow()).isEqualTo(3);

		// Repetir não repete o aviso: a conta já foi avisada nesta janela.
		assertThat(counter.record("user:1").alert()).isFalse();
		assertThat(counter.aboveThreshold()).isEqualTo(1);
	}

	@Test
	void avisoVoltaQuandoAJanelaEsvazia() {
		for (int i = 0; i < 3; i++) {
			counter.record("user:2");
		}
		assertThat(counter.inWindow("user:2", clock.millis())).isEqualTo(3);

		clock.advanceMinutes(11);
		assertThat(counter.inWindow("user:2", clock.millis())).isZero();
		assertThat(counter.aboveThreshold()).isZero();

		assertThat(counter.record("user:2").alert()).isFalse();
		assertThat(counter.record("user:2").alert()).isFalse();
		assertThat(counter.record("user:2").alert()).isTrue();
	}

	@Test
	void identidadesDiferentesNaoSomam() {
		for (int i = 0; i < 5; i++) {
			assertThat(counter.windowed("anon:" + i, clock.millis()).alert()).isFalse();
		}
		assertThat(counter.aboveThreshold()).isZero();
	}

	@Test
	void identidadeVaziaViraUmBaldeUnico() {
		assertThat(counter.windowed("", clock.millis()).alert()).isFalse();
		assertThat(counter.windowed(null, clock.millis()).alert()).isFalse();
		assertThat(counter.windowed(" ", clock.millis()).alert()).isTrue();
	}

	@Test
	void configuracaoInvalidaNaoDesligaOContador() {
		AdminDenialCounter zero = new AdminDenialCounter(clock, 0, 0);
		assertThat(zero.record("user:3").alert()).isTrue();
	}

	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		private void advanceMinutes(long minutes) {
			now = now.plusSeconds(minutes * 60);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}