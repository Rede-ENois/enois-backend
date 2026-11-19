package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** A série da emissão do stream distingue ok, slow, closed e error, com cardinalidade fixa. */
class StreamEmissionMetricsTests {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private StreamEmissionMetrics metrics() {
		return new StreamEmissionMetrics(new ObjectProvider<>() {
			@Override
			public io.micrometer.core.instrument.MeterRegistry getIfAvailable() {
				return registry;
			}
		});
	}

	private Timer timer(String outcome) {
		return registry.find(StreamEmissionMetrics.TIMER_NAME).tags("outcome", outcome).timer();
	}

	@Test
	void emissaoRapidaFicaComoOkNaSerie() {
		metrics().record(StreamEmissionMetrics.Outcome.OK, System.nanoTime());
		assertThat(timer("ok")).isNotNull();
		assertThat(timer("ok").count()).isEqualTo(1);
	}

	@Test
	void emissaoAcimaDoLimiarEClassificadaComoSlow() {
		StreamEmissionMetrics m = metrics();
		assertThat(m.aboveThreshold(System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(600)))
			.isEqualTo(StreamEmissionMetrics.Outcome.SLOW);
		assertThat(m.aboveThreshold(System.nanoTime())).isEqualTo(StreamEmissionMetrics.Outcome.OK);
	}

	@Test
	void semRegistryNadaERegistrado() {
		new StreamEmissionMetrics(new ObjectProvider<io.micrometer.core.instrument.MeterRegistry>() {
			@Override
			public io.micrometer.core.instrument.MeterRegistry getIfAvailable() {
				return null;
			}
		}).record(StreamEmissionMetrics.Outcome.OK, System.nanoTime());
		assertThat(registry.find(StreamEmissionMetrics.TIMER_NAME).timers()).isEmpty();
	}

	@Test
	void aSerieTemCardinalidadeFixaDeUmaTag() {
		metrics().record(StreamEmissionMetrics.Outcome.OK, System.nanoTime());
		metrics().record(StreamEmissionMetrics.Outcome.CLOSED, System.nanoTime());
		assertThat(registry.find(StreamEmissionMetrics.TIMER_NAME).timers()).hasSize(2);
		Timer unico = registry.find(StreamEmissionMetrics.TIMER_NAME).tags("outcome", "closed").timer();
		assertThat(unico).isNotNull();
		assertThat(unico.getId().getTags()).hasSize(1);
	}
}
