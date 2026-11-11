package com.socialcoisa.rede.shared.broadcast;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lê os sinais das outras instâncias e entrega no estado local desta.
 *
 * <p>É o outro lado do {@link InstanceBroadcaster}. Cada instância tem a sua própria posição de
 * leitura ({@code lastSeq}); nada aqui reserva, consome ou apaga sinal de outra instância — o que é
 * exatamente o que um handler do outbox não conseguiria fazer (claim + recibo entregam em uma só).</p>
 *
 * <p>A entrega é isolada por sinal: um sinal que estoura no consumidor não impede a entrega dos
 * vizinhos do lote, e a posição avança mesmo assim. Se fosse tudo-ou-nada, um sinal envenenado
 * pararia o fan-out inteiro — e o sinal é aceleração, não garantia.</p>
 *
 * <p>Ao subir, a instância começa a ler do fim da tabela: sinal emitido antes de ela existir não
 * tem estado local para atualizar, e reler o histórico só gastaria trabalho.</p>
 */
@Component
class InstanceSignalPoller {

	private static final Logger LOG = LoggerFactory.getLogger(InstanceSignalPoller.class);

	private static final int BATCH_SIZE = 200;

	private final InstanceSignalStore store;
	private final InstanceBroadcaster broadcaster;
	private final List<LocalSignalConsumer> consumers;
	private final Clock clock;
	private final Duration retention;
	private final boolean enabled;
	private final boolean scheduled;
	private final AtomicLong lastSeq = new AtomicLong(-1);

	InstanceSignalPoller(InstanceSignalStore store, InstanceBroadcaster broadcaster,
			List<LocalSignalConsumer> consumers, Clock clock,
			@Value("${rede.instance.broadcast.enabled:true}") boolean enabled,
			@Value("${rede.instance.broadcast.scheduled:true}") boolean scheduled,
			@Value("${rede.instance.broadcast.retention-seconds:300}") long retentionSeconds) {
		this.store = store;
		this.broadcaster = broadcaster;
		this.consumers = consumers;
		this.clock = clock;
		this.enabled = enabled;
		this.scheduled = scheduled;
		this.retention = Duration.ofSeconds(Math.max(60, retentionSeconds));
	}

	@Scheduled(fixedDelayString = "${rede.instance.broadcast.delay-ms:1000}",
			initialDelayString = "${rede.instance.broadcast.initial-delay-ms:2000}")
	void sweep() {
		if (!enabled || !scheduled) {
			return;
		}
		runOnce();
	}

	/**
	 * Uma rodada: entrega o que chegou e anuncia presença.
	 *
	 * @return quantos sinais foram entregues nesta instância
	 */
	int runOnce() {
		Instant now = clock.instant();
		int delivered = 0;
		try {
			long position = position();
			List<InstanceSignal> pending = store.pending(broadcaster.instanceId(), position, BATCH_SIZE);
			Map<String, LocalSignalConsumer> byType = byType();
			long highest = position;
			Instant lastSignalAt = null;
			for (InstanceSignal signal : pending) {
				LocalSignalConsumer consumer = byType.get(signal.type());
				if (consumer != null) {
					try {
						consumer.deliver(signal);
						delivered++;
					}
					catch (RuntimeException failure) {
						LOG.warn("Sinal {} ({}) nao entregou nesta instancia ({}); o TTL cobre o atraso",
							signal.id(), signal.type(), failure.getClass().getSimpleName());
					}
				}
				// Sinal sem consumidor também avança a posição: não adianta reler para sempre.
				highest = Math.max(highest, signal.seq());
				lastSignalAt = signal.createdAt();
			}
			lastSeq.set(highest);
			store.heartbeatReader(broadcaster.instanceId(), now, highest, lastSignalAt);
			if (delivered > 0) {
				LOG.debug("Sinais entregues nesta instancia: {}", delivered);
			}
		}
		catch (RuntimeException failure) {
			// A varredura não pode morrer; o próximo ciclo lê de novo, porque a posição não andou.
			LOG.error("Varredura de sinais entre instancias falhou", failure);
		}
		return delivered;
	}

	/**
	 * Retenção: sinal é para ser lido em segundos, não guardado. Apaga o que passou da janela e
	 * registra de instância há muito tempo sem heartbeat.
	 */
	@Scheduled(fixedDelayString = "${rede.instance.broadcast.retention-delay-ms:60000}",
			initialDelayString = "${rede.instance.broadcast.retention-initial-delay-ms:60000}")
	void purge() {
		if (!enabled || !scheduled) {
			return;
		}
		try {
			Instant threshold = clock.instant().minus(retention);
			int removed = store.purgeOlderThan(threshold, 500);
			int readers = store.purgeSilentReaders(clock.instant().minus(Duration.ofMinutes(10)));
			if (removed > 0 || readers > 0) {
				LOG.debug("Sinais podados: {}, instancias silenciosas: {}", removed, readers);
			}
		}
		catch (RuntimeException failure) {
			LOG.warn("Falha ao podar sinais entre instancias ({})", failure.getClass().getSimpleName());
		}
	}

	/** Posição de leitura desta instância; na primeira rodada, o fim da tabela. */
	private long position() {
		long current = lastSeq.get();
		if (current >= 0) {
			return current;
		}
		long tail = store.maxSeq();
		lastSeq.compareAndSet(-1, tail);
		return lastSeq.get();
	}

	private Map<String, LocalSignalConsumer> byType() {
		Map<String, LocalSignalConsumer> map = new HashMap<>();
		for (LocalSignalConsumer consumer : consumers) {
			map.put(consumer.signalType(), consumer);
		}
		return map;
	}

	/** Uso de diagnóstico e de teste. */
	int consumerCount() {
		return consumers.size();
	}

	/** Uso de teste: força a releitura desde o início da tabela. */
	void rewind() {
		lastSeq.set(0);
	}

	/** Uso de teste: fixa a posição de leitura, para ler só o que foi emitido depois dela. */
	void seek(long seq) {
		lastSeq.set(Math.max(0, seq));
	}
}
