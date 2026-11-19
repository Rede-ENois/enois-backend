package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static com.socialcoisa.rede.media.application.MediaUploadStageMetrics.Outcome;

/**
 * As etapas do upload de mídia aparecem na série com resultado técnico (#710): etapa custosa
 * vira leitura de timer, etapa bloqueada continua bloqueando e aparece como blocked. Sem
 * registry, nada é registrado (testes que montam o controller na mão seguem funcionando).
 */
class MediaUploadStageMetricsTests {

	private SimpleMeterRegistry registry;
	private MediaUploadStageMetrics metrics;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		metrics = comDestaque(registry);
	}

	private static MediaUploadStageMetrics comDestaque(SimpleMeterRegistry alvo) {
		return new MediaUploadStageMetrics(new ObjectProvider<>() {
			@Override
			public MeterRegistry getIfAvailable() {
				return alvo;
			}

			@Override
			public MeterRegistry getObject(Object... args) {
				return alvo;
			}

			@Override
			public MeterRegistry getObject() {
				return alvo;
			}

			@Override
			public MeterRegistry getIfUnique() {
				return alvo;
			}
		});
	}

	@AfterEach
	void tearDown() {
		registry.close();
	}

	@Test
	void etapaCustosaViraLeituraDeTimerComEtapaEResultado() {
		metrics.record("nsfw", Outcome.OK, System.nanoTime() - 12_000_000L);

		Timer timer = registry.find("rede_media_upload_stage")
			.tags("stage", "nsfw", "outcome", "ok")
			.timer();
		assertThat(timer).as("timer da etapa nsfw").isNotNull();
		assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(12.0);
	}

	@Test
	void etapaQueRecusaOConteudoApareceComoBlockedSemDeixarDeSerBlocked() {
		// A classificação do resultado é de quem chama; aqui só se prova que blocked é uma série
		// própria, separada de ok — o comportamento de bloqueio em si é dos testes do serviço.
		metrics.record("nsfw", Outcome.BLOCKED, System.nanoTime() - 5_000_000L);
		metrics.record("nsfw", Outcome.OK, System.nanoTime());

		assertThat(registry.find("rede_media_upload_stage")
			.tags("stage", "nsfw", "outcome", "blocked").timer()).isNotNull();
		assertThat(registry.find("rede_media_upload_stage")
			.tags("stage", "nsfw", "outcome", "ok").timer().count()).isEqualTo(1);
	}

	@Test
	void semRegistryNadaERegistradoENadaExplode() {
		MediaUploadStageMetrics semRegistry =
			new MediaUploadStageMetrics(new ObjectProvider<>() {
				@Override
				public MeterRegistry getIfAvailable() {
					return null;
				}

				@Override
				public MeterRegistry getObject(Object... args) {
					throw new IllegalStateException("não deveria ser chamado");
				}

				@Override
				public MeterRegistry getObject() {
					throw new IllegalStateException("não deveria ser chamado");
				}

				@Override
				public MeterRegistry getIfUnique() {
					return null;
				}
			});

		semRegistry.record("sanitize", Outcome.OK, System.nanoTime());

		assertThat(registry.find("rede_media_upload_stage").timer()).isNull();
	}

	@Test
	void cardenalidadeFixaNaoLevaConteudoNemIdentificador() {
		metrics.record("write", Outcome.ERROR, System.nanoTime());

		// Só existe a série de etapas registrada por este helper, com as duas tags combinadas;
		// nenhuma série por mimetype/id aparece.
		assertThat(registry.find("rede_media_upload_stage").timers()).hasSize(1);
		assertThat(registry.find("rede_media_upload_stage").timers().iterator().next().getId().getTags())
			.anyMatch(tag -> tag.getKey().equals("stage") && tag.getValue().equals("write"))
			.anyMatch(tag -> tag.getKey().equals("outcome") && tag.getValue().equals("error"));
	}
}
