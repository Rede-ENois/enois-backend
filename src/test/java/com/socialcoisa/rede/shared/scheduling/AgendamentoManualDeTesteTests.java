package com.socialcoisa.rede.shared.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.NoOpTaskScheduler;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class AgendamentoManualDeTesteTests {

	@Autowired
	private TaskScheduler agendador;

	@Test
	void agendamentosFicamSobControleDoTesteSemExecutarWorkersEmSegundoPlano() {
		assertThat(agendador).isInstanceOf(NoOpTaskScheduler.class);
	}
}
