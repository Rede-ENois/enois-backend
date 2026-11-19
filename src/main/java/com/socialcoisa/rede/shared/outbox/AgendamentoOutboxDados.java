package com.socialcoisa.rede.shared.outbox;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@ConditionalOnProperty(name = "rede.dados.outbox-enabled", havingValue = "true")
public class AgendamentoOutboxDados {

	@Bean
	ThreadPoolTaskScheduler agendadorOutboxDados() {
		var agendador = new ThreadPoolTaskScheduler();
		agendador.setThreadNamePrefix("dados-outbox-");
		agendador.setWaitForTasksToCompleteOnShutdown(true);
		agendador.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
		agendador.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		agendador.setAwaitTerminationSeconds(15);
		return agendador;
	}

	@Bean
	SmartInitializingSingleton registrarOutboxDados(
			@Qualifier("agendadorOutboxDados") ThreadPoolTaskScheduler agendador,
			OutboxWorker outbox, OutboxRetentionWorker retencao,
			@Value("${rede.outbox.worker-initial-delay-ms:0}") long inicioProcessamento,
			@Value("${rede.outbox.worker-delay-ms:5000}") long intervaloProcessamento,
			@Value("${rede.outbox.retention.initial-delay-ms:600000}") long inicioRetencao,
			@Value("${rede.outbox.retention.interval-ms:3600000}") long intervaloRetencao) {
		if (inicioProcessamento < 0 || inicioRetencao < 0 || intervaloProcessamento <= 0 || intervaloRetencao <= 0) {
			throw new IllegalArgumentException("A agenda de outbox exige atrasos não negativos e intervalos positivos.");
		}
		return () -> {
			var agora = Instant.now();
			agendador.scheduleWithFixedDelay(outbox::processPendingEvents,
				agora.plusMillis(inicioProcessamento), Duration.ofMillis(intervaloProcessamento));
			agendador.scheduleWithFixedDelay(retencao::run,
				agora.plusMillis(inicioRetencao), Duration.ofMillis(intervaloRetencao));
		};
	}
}
