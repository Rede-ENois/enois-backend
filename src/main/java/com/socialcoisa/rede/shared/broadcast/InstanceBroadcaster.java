package com.socialcoisa.rede.shared.broadcast;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Emite sinais para as outras instâncias do Control Plane.
 *
 * <p>Duas escolhas que valem explicação:</p>
 * <ul>
 * <li><b>Publica depois do commit, com o efeito já aplicado localmente.</b> Quem chama primeiro
 * aplica o efeito no próprio pod (limpa o cache, avisa o próprio stream) e depois grava o sinal
 * para os outros. Gravar antes deixaria as outras instâncias avisadas de algo que pode não
 * commitar; gravar sem aplicar localmente faria a própria instância esperar o próximo ciclo de
 * leitura para ver o próprio efeito.</li>
 * <li><b>Falha ao gravar sinal não derruba a escrita.</b> Broadcast é aceleração: o TTL do cache e
 * o reconnect do EventSource cobrem a perda. Derrubar a requisição do usuário porque a tabela de
 * aviso falhou inverteria a prioridade — o efeito de negócio é a autoridade, o aviso é conveniência.
 * O erro fica no log.</li>
 * </ul>
 *
 * <p>Sem transação aberta o sinal é gravado na hora, para o caso de chamada fora de
 * {@code @Transactional} e para os testes.</p>
 */
@Component
public class InstanceBroadcaster {

	private static final Logger LOG = LoggerFactory.getLogger(InstanceBroadcaster.class);

	private final InstanceSignalStore store;
	private final Clock clock;
	private final String instanceId;
	private final boolean enabled;

	InstanceBroadcaster(InstanceSignalStore store, Clock clock,
			@Value("${rede.instance.id:}") String instanceId,
			@Value("${rede.instance.broadcast.enabled:true}") boolean enabled) {
		this.store = store;
		this.clock = clock;
		this.instanceId = resolveInstanceId(instanceId);
		this.enabled = enabled;
	}

	/** Identidade desta instância. Explícita por configuração; gerada quando não há. */
	public String instanceId() {
		return instanceId;
	}

	public boolean enabled() {
		return enabled;
	}

	/**
	 * Emite sinal para as outras instâncias. Se a transação atual existe, só grava no commit.
	 *
	 * @param type tipo do sinal, ver {@link InstanceSignals}
	 * @param targets alvos da entrega; vazio não emite nada
	 * @param payload dados mínimos da entrega (só ids, nunca conteúdo)
	 */
	public void publish(String type, Collection<String> targets, String payload) {
		if (!enabled) {
			return;
		}
		for (String target : targets) {
			if (target == null || target.isBlank()) {
				continue;
			}
			String signalPayload = payload == null ? "{}" : payload;
			if (TransactionSynchronizationManager.isSynchronizationActive()) {
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
					@Override
					public void afterCommit() {
						write(type, target, signalPayload);
					}
				});
				continue;
			}
			write(type, target, signalPayload);
		}
	}

	/**
	 * Emite sinal na hora, sem esperar commit.
	 *
	 * <p>Para quem já está em {@code AFTER_COMMIT}: registrar outra sincronização ali nunca rodaria,
	 * porque a transação já terminou. Payload nulo não vira sinal — sem dados a entrega local não
	 * teria o que montar.</p>
	 */
	public void publishNow(String type, Collection<String> targets, String payload) {
		if (!enabled || payload == null) {
			return;
		}
		for (String target : targets) {
			if (target == null || target.isBlank()) {
				continue;
			}
			write(type, target, payload);
		}
	}

	private void write(String type, String target, String payload) {
		InstanceSignal signal = new InstanceSignal(UUID.randomUUID().toString(), instanceId, type, target, payload,
			clock.instant());
		try {
			store.insert(signal);
		}
		catch (RuntimeException failure) {
			// Broadcast é aceleração, não garantia: o efeito já commitou e continua valendo.
			LOG.warn("Falha ao emitir sinal {} para {} ({}): a outra instancia avista pelo TTL",
				type, target, failure.getClass().getSimpleName());
		}
	}

	private static String resolveInstanceId(String configured) {
		if (configured != null && !configured.isBlank()) {
			return configured.length() > 64 ? configured.substring(0, 64) : configured;
		}
		return "pod-" + UUID.randomUUID().toString().substring(0, 12);
	}

	/** Uso de diagnóstico: instante do relógio injetado, que é o das leituras e da retenção. */
	Instant now() {
		return clock.instant();
	}
}
