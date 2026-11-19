package com.socialcoisa.rede.media.application;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Tempo por etapa do upload de mídia (#710): sanitize, verificação NSFW, gravação no bucket.
 *
 * <p>Observação pura: não muda resposta, ordem das etapas nem a barreira de validação — uma etapa
 * bloqueada continua bloqueando exatamente como antes; o que muda é que o custo dela aparece com
 * resultado técnico na série. Cardinalidade fixa: as tags são o nome da etapa e o resultado
 * (ok/blocked/error), nunca mimetype, id de asset ou caminho.</p>
 */
@Component
public class MediaUploadStageMetrics {

	private final ObjectProvider<MeterRegistry> meters;

	public MediaUploadStageMetrics(ObjectProvider<MeterRegistry> meters) {
		this.meters = meters;
	}

	/** Resultado técnico da etapa; a decisão de bloqueio continua sendo da própria etapa. */
	public enum Outcome {
		/** A etapa terminou e o fluxo seguiu. */
		OK,
		/** A etapa recusou o conteúdo (validação, política) — comportamento esperado. */
		BLOCKED,
		/** A etapa falhou por motivo técnico (recurso externo, erro inesperado). */
		ERROR
	}

	/**
	 * Registra a duração da etapa. A decisão de classificar o resultado como BLOCKED, ERROR ou OK
	 * é de quem chama — aqui só existe a série.
	 */
	public void record(String stage, Outcome outcome, long startedAtNanos) {
		MeterRegistry registry = meters.getIfAvailable();
		if (registry == null) {
			return;
		}
		Timer.builder("rede_media_upload_stage")
			.tag("stage", stage)
			.tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
			.description("Duração de cada etapa do upload de mídia")
			.publishPercentiles(0.5, 0.95)
			.register(registry)
			.record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
	}
}
