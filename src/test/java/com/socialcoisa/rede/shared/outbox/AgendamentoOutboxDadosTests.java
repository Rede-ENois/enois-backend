package com.socialcoisa.rede.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class AgendamentoOutboxDadosTests {

	private final OutboxWorker outbox = mock(OutboxWorker.class);
	private final OutboxRetentionWorker retencao = mock(OutboxRetentionWorker.class);
	private final ApplicationContextRunner contexto = new ApplicationContextRunner()
		.withUserConfiguration(AgendamentoOutboxDados.class)
		.withBean(OutboxWorker.class, () -> outbox)
		.withBean(OutboxRetentionWorker.class, () -> retencao);

	@Test
	void permaneceDesligadoPorPadrao() {
		contexto.run(ctx -> assertThat(ctx).doesNotHaveBean(ThreadPoolTaskScheduler.class));
	}

	@Test
	void rodaProcessamentoERetencaoQuandoHabilitado() {
		contexto.withPropertyValues("rede.dados.outbox-enabled=true",
				"rede.outbox.worker-initial-delay-ms=0", "rede.outbox.retention.initial-delay-ms=0")
			.run(ctx -> {
				assertThat(ctx).hasSingleBean(ThreadPoolTaskScheduler.class);
				verify(outbox, timeout(1500)).processPendingEvents();
				verify(retencao, timeout(1500)).run();
			});
	}
}
