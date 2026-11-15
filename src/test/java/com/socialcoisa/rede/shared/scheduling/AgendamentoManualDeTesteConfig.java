package com.socialcoisa.rede.shared.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.NoOpTaskScheduler;

/** O classpath de teste registra tarefas, mas os cenarios disparam os workers explicitamente. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.task.scheduling.enabled", havingValue = "false")
class AgendamentoManualDeTesteConfig {

	@Bean
	TaskScheduler taskScheduler() {
		return new NoOpTaskScheduler();
	}
}
