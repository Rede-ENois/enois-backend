package com.socialcoisa.rede.network.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Um nó da rede cooperativa: um servidor autorizado que executa trabalho do Énóis.
 *
 * <p>A identidade é do nó, não do dono — o dono não herda permissão nenhuma. Permissão é dado do
 * Control Plane (esta tabela), e o nó só lê a própria configuração.</p>
 *
 * <p>Capacidade declarada é o que o participante diz ter; observada é o que o heartbeat mediu. O
 * scheduler usa a observada, porque é a única que não depende da boa-fé de quem declara. As duas
 * existem porque a declarada é o teto que o participante aceitou ceder.</p>
 *
 * @param id identificador estável do nó (nd_…)
 * @param displayName nome legível para o painel
 * @param ownerUserId conta responsável pelo participante (auditoria e contato)
 * @param status estado atual; REVOKED é terminal
 * @param profiles perfis autorizados; vazio = nenhum tipo de tarefa
 * @param region região declarada pelo participante
 * @param country país declarado (ISO-2), usado na restrição de dado
 * @param runtimeVersion versão do runtime reportada no último heartbeat
 * @param lastSeenAt último heartbeat; null = nunca conectou
 * @param declaredCpuCores núcleos que o participante cedeu
 * @param declaredMemoryMb memória que o participante cedeu
 * @param declaredDiskMb disco que o participante cedeu; sem consumidor hoje, vai na config assinada
 * @param declaredBandwidthMbps banda que o participante cedeu; sem consumidor hoje, vai na config
 * @param observedCpuPercent uso de CPU no último heartbeat; null = não reportado
 * @param observedMemoryMb memória em uso no último heartbeat; null = não reportado
 * @param observedRunningTasks tarefas em execução no último heartbeat; null = não reportado
 * @param observedLatencyMs mediana do tempo de conclusão das últimas tarefas; null = sem amostra
 * @param consecutiveFailures resultados falhos seguidos; um sucesso zera a contagem
 * @param quarantinedUntil fora do balanceamento até este instante; null = em balanceamento
 * @param quarantineReason por que o nó foi tirado do balanceamento; null = nunca foi
 */
public record NetworkNode(String id, String displayName, String ownerUserId, NodeStatus status,
		Set<NodeProfile> profiles, String region, String country, String runtimeVersion, Instant lastSeenAt,
		int declaredCpuCores, int declaredMemoryMb, int declaredDiskMb, int declaredBandwidthMbps,
		Double observedCpuPercent, Integer observedMemoryMb, Integer observedRunningTasks, Integer observedLatencyMs,
		int consecutiveFailures, Instant quarantinedUntil, String quarantineReason) {

	/** Acima disto o nó está ocupado demais para receber tarefa nova, mesmo com vaga declarada. */
	public static final double SATURATED_CPU_PERCENT = 85.0;

	/**
	 * Acima desta latência o nó é <b>lento</b>: continua recebendo trabalho, mas só depois dos nós
	 * dentro da faixa. Remover um nó lento seria desproporcional — ele ainda entrega —, e tratá-lo
	 * igual a um nó rápido concentraria a fila nele.
	 */
	public static final int SLOW_LATENCY_MS = 30_000;

	/** Falhas seguidas que tiram o nó do balanceamento por uma janela. */
	public static final int FAILURE_STREAK_LIMIT = 3;

	public NetworkNode {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("Node id vazio.");
		}
		if (displayName == null || displayName.isBlank()) {
			throw new IllegalArgumentException("Node displayName vazio.");
		}
		if (ownerUserId == null || ownerUserId.isBlank()) {
			throw new IllegalArgumentException("Node ownerUserId vazio.");
		}
		if (status == null) {
			throw new IllegalArgumentException("Node status nulo.");
		}
		profiles = profiles == null ? Set.of() : Set.copyOf(profiles);
	}

	public boolean canOperate() {
		return status.canOperate() && !profiles.isEmpty();
	}

	/**
	 * Quantas tarefas este nó aceita ao mesmo tempo. O piso é 1 (um nó que declarou zero núcleos
	 * ainda pode executar uma tarefa — senão um cadastro mal preenchido viraria nó inútil), e o
	 * teto é o que ele declarou ceder.
	 */
	public int concurrencyLimit() {
		return Math.max(1, declaredCpuCores);
	}

	/** Tarefas em execução segundo o último heartbeat; sem reporte, assume zero. */
	public int runningTasks() {
		return observedRunningTasks == null ? 0 : Math.max(0, observedRunningTasks);
	}

	/** Tem vaga para mais uma tarefa, pelo que ele mesmo reportou? */
	public boolean hasCapacity() {
		return runningTasks() < concurrencyLimit();
	}

	/** CPU observada acima do limiar: o nó está saturado mesmo que ainda tenha vaga declarada. */
	public boolean saturated() {
		return observedCpuPercent != null && observedCpuPercent >= SATURATED_CPU_PERCENT;
	}

	/**
	 * A memória observada já bateu o teto que o participante declarou ceder? É o limite dele, não
	 * uma estimativa nossa: o nó aceitou ceder até {@code declaredMemoryMb}, e designar tarefa nova
	 * a partir daí é passar por cima do combinado — o container dele estoura e a tarefa morre com
	 * ele.
	 *
	 * <p>Sem teto declarado não há limite a respeitar (mesmo raciocínio do piso 1 em
	 * {@link #concurrencyLimit()}), e sem reporte não há o que comparar: não medir não é estar
	 * cheio. Nos dois casos o nó continua elegível.</p>
	 */
	public boolean memoryExhausted() {
		return declaredMemoryMb > 0 && observedMemoryMb != null && observedMemoryMb >= declaredMemoryMb;
	}

	/**
	 * O nó está vivo? Um nó que parou de mandar heartbeat não recebe tarefa nova: a tarefa ficaria
	 * parada até o claim expirar, e o certo é escolher outro.
	 */
	public boolean alive(Instant now, Duration staleAfter) {
		return lastSeenAt != null && lastSeenAt.isAfter(now.minus(staleAfter));
	}

	/**
	 * O nó está fora do balanceamento agora? Janela vencida não conta: o nó volta sozinho ao
	 * balanceamento quando o prazo passa, sem ninguém precisar religar nada.
	 */
	public boolean quarantined(Instant now) {
		return quarantinedUntil != null && quarantinedUntil.isAfter(now);
	}

	/** Está falhando a ponto de sair do balanceamento? O streak é o sinal, não o erro isolado. */
	public boolean failing() {
		return consecutiveFailures >= FAILURE_STREAK_LIMIT;
	}

	/** Tem amostra de latência acima da faixa considerada normal? Sem amostra não é lento. */
	public boolean slow() {
		return observedLatencyMs != null && observedLatencyMs > SLOW_LATENCY_MS;
	}

	/** Pode receber tarefa deste tipo e desta classe de dado? */
	public boolean accepts(TaskType type, DataClass dataClass) {
		if (!canOperate() || !type.allowedFor(profiles)) {
			return false;
		}
		// Dado que não pode sair da infraestrutura autorizada só vai para nó próprio.
		return dataClass.mayLeaveAuthorizedInfra() || profiles.contains(NodeProfile.NODE_LOCAL);
	}
}
