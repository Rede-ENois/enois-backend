package com.socialcoisa.rede.network.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Varredura periódica do scheduler.
 *
 * <p>O handler do outbox designa quando chega tarefa nova; esta varredura existe para o que não
 * depende de evento novo: tarefa cujo claim venceu (o nó caiu), tarefa que ficou na fila porque não
 * havia nó elegível, e o recálculo do modo operacional. Sem ela, uma tarefa órfã só voltaria à fila
 * quando outro evento chegasse.</p>
 *
 * <p>Roda com o mesmo intervalo do worker do outbox por padrão, e é desligável por propriedade para
 * a suíte de testes chamar {@code runOnce()} na mão.</p>
 */
@Component
class NetworkSchedulerWorker {

	private static final Logger LOG = LoggerFactory.getLogger(NetworkSchedulerWorker.class);

	private final NetworkTaskService tasks;
	private final NetworkModeService modes;
	private final java.time.Clock clock;
	private final boolean enabled;

	NetworkSchedulerWorker(NetworkTaskService tasks, NetworkModeService modes, java.time.Clock clock,
			@Value("${rede.network.scheduler.enabled:true}") boolean enabled) {
		this.tasks = tasks;
		this.modes = modes;
		this.clock = clock;
		this.enabled = enabled;
	}

	@Scheduled(fixedDelayString = "${rede.network.scheduler.delay-ms:5000}")
	void sweep() {
		if (!enabled) {
			return;
		}
		runOnce();
	}

	/** Uma rodada: devolve órfãs, designa pendentes, recalcula o modo. */
	void runOnce() {
		try {
			int reclaimed = tasks.reclaimAbandoned(clock.instant());
			int dispatched = tasks.dispatchPending();
			modes.evaluate();
			if (reclaimed > 0 || dispatched > 0) {
				LOG.debug("Scheduler: {} recuperadas, {} designadas", reclaimed, dispatched);
			}
		}
		catch (RuntimeException exception) {
			// A varredura não pode morrer: o próximo ciclo tenta de novo. O erro fica no log.
			LOG.error("Varredura do scheduler falhou", exception);
		}
	}
}
