package com.socialcoisa.rede.messaging.adapters.in.web;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.ObjectProvider;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Métrica da emissão no stream de mensagens (#707): o send de um {@link SseEmitter} é síncrono e
 * roda na thread que confirmou a escrita, então um receptor lento atrasa a resposta do POST que
 * gerou o aviso. O timer mede quanto cada emissão custa e o contador de lentos separa as emissões
 * que passam do limiar de atenção. Observação pura: nenhuma ordem, política ou resposta muda.
 */
class StreamEmissionMetrics {

	/** Timer único da emissão; tags fixas para cardinalidade constante. */
	static final String TIMER_NAME = "rede_stream_emissao";
	/** Emissões acima disso viram evento no contador de lentos (risco de backpressure). */
	static final long SLOW_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

	private final ObjectProvider<MeterRegistry> meters;

	StreamEmissionMetrics(ObjectProvider<MeterRegistry> meters) {
		this.meters = meters;
	}

	enum Outcome {
		/** Emissão concluída dentro do limiar. */
		OK,
		/** Emissão concluída, mas acima do limiar de atenção. */
		SLOW,
		/** Receptor fechado (aba morta): custo nulo, o emitter sai do registro. */
		CLOSED,
		/** Falha técnica inesperada na emissão. */
		ERROR
	}

	/**
	 * Mede a emissão já concluída. {@code outcome} SLOW e OK se distinguem só pelo limiar — o
	 * chamador decide entre eles com o mesmo valor medido, por isso ele entra pronto.
	 */
	void record(Outcome outcome, long startedAtNanos) {
		MeterRegistry registry = meters.getIfAvailable();
		if (registry == null) {
			return;
		}
		Timer.builder(TIMER_NAME).tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
			.description("Duração da emissão de evento no stream de mensagens")
			.publishPercentiles(0.5, 0.95).register(registry)
			.record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
	}

	Outcome aboveThreshold(long startedAtNanos) {
		return System.nanoTime() - startedAtNanos > SLOW_NANOS ? Outcome.SLOW : Outcome.OK;
	}
}
