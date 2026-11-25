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
 * @param declaredDiskMb disco que o participante cedeu; vai na config assinada e é o runtime do nó quem faz valer (teto do conteúdo no volume)
 * @param declaredBandwidthMbps banda que o participante cedeu; vai na config assinada e é o runtime do nó quem faz valer (teto de taxa das transferências)
 * @param observedCpuPercent uso de CPU no último heartbeat; null = não reportado
 * @param observedMemoryMb memória <b>em uso</b> no último heartbeat; null = não reportado (nem
 * derivado). É o uso, não o disponível: é esta que {@link #memoryExhausted()} compara com o teto
 * @param observedMemoryAvailableMb memória <b>disponível</b> medida no último heartbeat; null =
 * não reportado — e o uso derivado quando o runtime só manda o disponível (runtime antigo)
 * @param observedRunningTasks tarefas em execução no último heartbeat; null = não reportado
 * @param observedDiskFreeMb disco livre medido pelo nó no último heartbeat; null = não reportado
 * (runtime antigo), diferente de zero, que é "sem espaço". O declarado
 * ({@link #declaredDiskMb}) é o contrato; este é a medida.
 * @param observedDiskTotalMb tamanho do volume medido no último heartbeat; null = não reportado.
 * Com {@link #observedDiskFreeMb} é o que permite separar "espaço reservado pelo participante" do
 * "capacidade oferecida" (brief do Storage §7): a reserva é o total que não está a serviço do Énóis.
 * @param observedContentUsedMb espaço que o ESTADO DE CONTEÚDO ocupa no nó, medido por ele; null =
 * não reportado. É o "utilizado pelo Énóis" do §7 — distinto do livre, que qualquer processo derruba.
 * @param observedLatencyMs mediana do tempo de conclusão das últimas tarefas; null = sem amostra
 * @param consecutiveFailures resultados falhos seguidos; um sucesso zera a contagem
 * @param quarantinedUntil fora do balanceamento até este instante; null = em balanceamento
 * @param quarantineReason por que o nó foi tirado do balanceamento; null = nunca foi
 */
public record NetworkNode(String id, String displayName, String ownerUserId, NodeStatus status,
		Set<NodeProfile> profiles, String region, String country, String runtimeVersion, Instant lastSeenAt,
		int declaredCpuCores, int declaredMemoryMb, int declaredDiskMb, int declaredBandwidthMbps,
		Double observedCpuPercent, Integer observedMemoryMb, Integer observedMemoryAvailableMb,
		Integer observedRunningTasks, Integer observedLatencyMs, Long observedDiskFreeMb, Long observedDiskTotalMb,
		Long observedContentUsedMb, Long observedBytesInMb, Long observedBytesOutMb, int consecutiveFailures,
		Instant quarantinedUntil, String quarantineReason) {

	/**
	 * Mesmo nó sem o tráfego medido (runtime anterior à V345): os dois contadores ficam nulos, que
	 * é "não mediu". Existe pelo mesmo motivo dos construtores abaixo — vinte e cinco argumentos,
	 * com dois {@code Long} novos no meio, são fáceis de trocar de ordem.
	 */
	public NetworkNode(String id, String displayName, String ownerUserId, NodeStatus status,
			Set<NodeProfile> profiles, String region, String country, String runtimeVersion, Instant lastSeenAt,
			int declaredCpuCores, int declaredMemoryMb, int declaredDiskMb, int declaredBandwidthMbps,
			Double observedCpuPercent, Integer observedMemoryMb, Integer observedRunningTasks, Integer observedLatencyMs,
			Long observedDiskFreeMb, Long observedDiskTotalMb, Long observedContentUsedMb, int consecutiveFailures,
			Instant quarantinedUntil, String quarantineReason) {
		this(id, displayName, ownerUserId, status, profiles, region, country, runtimeVersion, lastSeenAt,
			declaredCpuCores, declaredMemoryMb, declaredDiskMb, declaredBandwidthMbps, observedCpuPercent,
			observedMemoryMb, null, observedRunningTasks, observedLatencyMs, observedDiskFreeMb, observedDiskTotalMb,
			observedContentUsedMb, null, null, consecutiveFailures, quarantinedUntil, quarantineReason);
	}

	/**
	 * Tráfego total medido pelo nó (entrada + saída), ou {@code null} se ele não reportou nenhum dos
	 * dois. Somar zero no que falta seria afirmar tráfego zero para quem só informou metade.
	 */
	public Long observedTrafficMb() {
		if (observedBytesInMb == null && observedBytesOutMb == null) {
			return null;
		}
		return (observedBytesInMb == null ? 0L : observedBytesInMb) + (observedBytesOutMb == null ? 0L : observedBytesOutMb);
	}

	/** Quanto do tráfego acumulado é de saída (0–100), ou {@code null} quando não há base. */
	public Double outboundSharePercent() {
		Long total = observedTrafficMb();
		if (total == null || total <= 0) {
			return null;
		}
		return (observedBytesOutMb == null ? 0.0 : observedBytesOutMb * 100.0) / total;
	}

	/**
	 * Mesmo nó com o disco livre medido: {@code null} = não reportado (runtime anterior a V321).
	 *
	 * <p>Existe para os chamadores que já montam o nó sem essa medida — leitores dos testes e código
	 * que nunca armazena — não precisarem repetir {@code null} no meio de vinte argumentos, onde é
	 * fácil trocar a ordem com {@code observedLatencyMs}. O construtor canônico continua sendo o do
	 * record.</p>
	 */
	public NetworkNode(String id, String displayName, String ownerUserId, NodeStatus status,
			Set<NodeProfile> profiles, String region, String country, String runtimeVersion, Instant lastSeenAt,
			int declaredCpuCores, int declaredMemoryMb, int declaredDiskMb, int declaredBandwidthMbps,
			Double observedCpuPercent, Integer observedMemoryMb, Integer observedRunningTasks, Integer observedLatencyMs,
			int consecutiveFailures, Instant quarantinedUntil, String quarantineReason) {
		this(id, displayName, ownerUserId, status, profiles, region, country, runtimeVersion, lastSeenAt,
				declaredCpuCores, declaredMemoryMb, declaredDiskMb, declaredBandwidthMbps, observedCpuPercent,
				observedMemoryMb, null, observedRunningTasks, observedLatencyMs, null, null, null, null, null,
				consecutiveFailures, quarantinedUntil, quarantineReason);
	}

	/**
	 * Mesmo nó com o disco livre medido e o tamanho não reportado (runtime anterior a V341).
	 *
	 * <p>Separado do construtor acima porque é o que a leitura do catálogo já montava: o total entrou
	 * depois, e quem constrói o nó com a medida do livre continua dizendo a verdade — enquanto o
	 * runtime não informar o tamanho, ele é desconhecido, não igual ao livre.</p>
	 */
	public NetworkNode(String id, String displayName, String ownerUserId, NodeStatus status,
			Set<NodeProfile> profiles, String region, String country, String runtimeVersion, Instant lastSeenAt,
			int declaredCpuCores, int declaredMemoryMb, int declaredDiskMb, int declaredBandwidthMbps,
			Double observedCpuPercent, Integer observedMemoryMb, Integer observedRunningTasks, Integer observedLatencyMs,
			Long observedDiskFreeMb, int consecutiveFailures, Instant quarantinedUntil, String quarantineReason) {
		this(id, displayName, ownerUserId, status, profiles, region, country, runtimeVersion, lastSeenAt,
				declaredCpuCores, declaredMemoryMb, declaredDiskMb, declaredBandwidthMbps, observedCpuPercent,
				observedMemoryMb, null, observedRunningTasks, observedLatencyMs, observedDiskFreeMb, null, null,
				null, null, consecutiveFailures, quarantinedUntil, quarantineReason);
	}

	/**
	 * Quanto o Énóis ocupa <b>relativamente</b> ao que o participante cedeu, em percentual.
	 *
	 * <p>É o "limite máximo de utilização" do brief §7 visto de quem opera: o mesmo teto chega ao
	 * runtime em {@code limits.diskMb}, mas só o rodapé do disco sabia disso. Um nó em 97% está a
	 * caminho de evictar cache ou recusar réplica, e o painel precisa dizer isso antes.</p>
	 *
	 * <p>{@code null} quando falta alguma das duas medidas (uso não reportado, ou teto zero — 0 no
	 * contrato significa "sem teto", não "teto de zero"). Pode passar de 100: teto é promessa, e um
	 * nó acima dele é exatamente o que se quer enxergar, não esconder.</p>
	 */
	public Double usedPercent() {
		if (observedContentUsedMb == null || declaredDiskMb <= 0) {
			return null;
		}
		return (observedContentUsedMb * 100.0) / declaredDiskMb;
	}

	/**
	 * O espaço que o participante reservou para si, em MB: o volume que <b>não</b> foi cedido, nunca
	 * abaixo de zero.
	 *
	 * <p>É a conta do brief do Storage §7 — "2 TB de disco, 500 GB reservados, 1,5 TB oferecidos" — com
	 * os dois números medidos: {@code total - declarado}. Devolve {@code null} quando falta o total:
	 * sem o tamanho do volume não há reserva a calcular, e {@code 0} diria "não reservou nada".</p>
	 *
	 * <p>Participante que cedeu mais do que o volume tem (declarou 2 TB num disco de 500 GB) tem
	 * reserva zero, não negativa: a divergência aparece em {@link #declaredDiskMb} contra o total, que
	 * é o lugar de corrigi-la — inventar reserva negativa esconderia o erro no painel.</p>
	 */
	public Long reservedDiskMb() {
		if (observedDiskTotalMb == null) {
			return null;
		}
		return Math.max(0L, observedDiskTotalMb - declaredDiskMb);
	}

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
	 * O uso de memória como o Control Plane o deve ler do que o runtime mandou.
	 *
	 * <p>Duas medidas, um campo na rede:</p>
	 * <ul>
	 * <li>{@code usedMb} informado → é o uso, e {@code availableMb} é o disponível;</li>
	 * <li>só {@code availableMb} (runtime anterior a V381, que mandava o <b>disponível</b> em
	 * {@code memoryMb}) → o uso é derivado, {@code declarado - disponível}, <b>quando</b> o
	 * disponível não passa do declarado;</li>
	 * <li>só {@code usedMb} → o disponível é desconhecido, e fica nulo.</li>
	 * </ul>
	 *
	 * <p>Disponível acima do declarado é medida de outra máquina, não uso negativo: derivar daria
	 * zero (ou menos), e "nada em uso" é o oposto do que se sabe. Nesse caso o uso fica nulo — não
	 * medido — e o nó continua elegível, como sem reporte nenhum.</p>
	 */
	public static Integer usageFrom(Integer usedMb, Integer availableMb, int declaredMemoryMb) {
		if (usedMb != null) {
			return usedMb;
		}
		if (availableMb == null || declaredMemoryMb <= 0 || availableMb > declaredMemoryMb) {
			return null;
		}
		return declaredMemoryMb - availableMb;
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
