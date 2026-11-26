package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.ContentVersionRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkTaskRepository;
import com.socialcoisa.rede.network.application.port.out.NodeObservabilityRepository;
import com.socialcoisa.rede.network.domain.ContentKind;
import com.socialcoisa.rede.network.domain.NodeHealth;
import com.socialcoisa.rede.network.domain.TaskStatus;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkRegions;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.TaskType;

/**
 * Agregados do topo do painel "Énóis Network".
 *
 * <p>O painel de nós lista até {@code MAX_LIST} linhas, mas nenhum número do topo vinha de dado:
 * total de nós, ativos, indisponíveis, em quarentena, capacidade somada, CPU e memória agregadas,
 * versões de runtime, distribuição por região e por perfil — tudo o que o contrato do painel pede
 * — tinha de ser contado de cabeça pelo admin. Este serviço devolve os números calculados sobre a
 * mesma lista que a tela já lê: a rede cooperativa é de centenas de nós, não de milhões, e uma
 * consulta que a tela já faz não precisa virar seis.</p>
 *
 * <p>Nada aqui é estado nem decisão: é leitura. O modo da rede continua sendo cálculo do
 * {@link NetworkModeService}, e a capacidade usada no modo continua a que ignora nó saturado,
 * esgotado e em quarentena — estes números são para o humano ver, não para o scheduler usar.</p>
 */
@Service
public class NetworkDashboardService {

	/** Mesmo teto da listagem: agregar além dele seria mostrar uma rede que a tela não enxerga. */
	private static final int MAX_LIST = 200;
	/** Mesmo stale do scheduler: "vivo" aqui é o mesmo vivo que recebe tarefa. */
	private static final long STALE_SECONDS = 180;

	private final NetworkNodeRepository nodes;
	private final NetworkRegionService regions;
	private final NetworkTaskRepository tasks;
	private final NodeObservabilityRepository samples;
	private final ContentVersionRepository versions;
	private final Clock clock;

	NetworkDashboardService(NetworkNodeRepository nodes, NetworkRegionService regions, NetworkTaskRepository tasks,
			NodeObservabilityRepository samples, ContentVersionRepository versions, Clock clock) {
		this.nodes = nodes;
		this.regions = regions;
		this.tasks = tasks;
		this.samples = samples;
		this.versions = versions;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Dashboard dashboard() {
		Instant now = clock.instant();
		List<NetworkNode> all = nodes.list(MAX_LIST);
		List<String> evacuated = regions.outOfService();

		int total = all.size();
		int active = 0;
		int unavailable = 0;
		int quarantined = 0;
		int alive = 0;
		int degraded = 0;
		int declaredCores = 0;
		int declaredMemoryMb = 0;
		int freeSlots = 0;
		Double cpuSum = 0.0;
		int cpuSamples = 0;
		int memorySumMb = 0;
		int memorySamples = 0;
		Map<String, Integer> byRegion = new TreeMap<>();
		Map<String, Integer> byProfile = new TreeMap<>();
		Map<String, Integer> byRuntime = new TreeMap<>();

		for (NetworkNode node : all) {
			if (node.status() == NodeStatus.ACTIVE) {
				active++;
				if (node.quarantined(now)) {
					quarantined++;
				}
				if (node.alive(now, Duration.ofSeconds(STALE_SECONDS))) {
					alive++;
				}
				if (node.failing() || node.slow() || node.quarantined(now)) {
					degraded++;
				}
				// Vaga conservadora: a mesma conta do modo — quem está saturado, esgotado de memória
				// ou fora do balanceamento não promete vaga que não vai cumprir. Saturado continua
				// fora de "degradado": carga momentânea não é saúde.
				if (node.hasCapacity() && !node.saturated() && !node.memoryExhausted()
						&& !node.quarantined(now) && !NetworkRegions.outOfService(evacuated, node.region())) {
					freeSlots += Math.max(0, node.concurrencyLimit() - node.runningTasks());
				}
			}
			else if (node.status() != NodeStatus.PENDING_APPROVAL) {
				unavailable++;
			}
			declaredCores += node.declaredCpuCores();
			declaredMemoryMb += node.declaredMemoryMb();
			if (node.observedCpuPercent() != null) {
				cpuSum += node.observedCpuPercent();
				cpuSamples++;
			}
			if (node.observedMemoryMb() != null) {
				memorySumMb += node.observedMemoryMb();
				memorySamples++;
			}
			if (node.region() != null && !node.region().isBlank()) {
				byRegion.merge(node.region(), 1, Integer::sum);
			}
			for (NodeProfile profile : node.profiles()) {
				byProfile.merge(profile.name(), 1, Integer::sum);
			}
			if (node.runtimeVersion() != null && !node.runtimeVersion().isBlank()) {
				byRuntime.merge(node.runtimeVersion(), 1, Integer::sum);
			}
		}

		Map<String, Integer> topRuntimes = new LinkedHashMap<>();
		byRuntime.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
			.forEach(entry -> topRuntimes.put(entry.getKey(), entry.getValue()));

		// A cobertura é calculada uma vez: ela é o bloco `workTypes` da tela E o que decide o alerta
		// "trabalho que ninguém pode fazer". Duas contas seriam duas respostas para o mesmo quadro.
		List<WorkCoverage> cobertura = workTypes(all, evacuated, now);
		return new Dashboard(total, active, unavailable, quarantined, alive, degraded, declaredCores,
			declaredMemoryMb, freeSlots, cpuSamples == 0 ? null : cpuSum / cpuSamples,
			cpuSamples, memorySamples == 0 ? null : memorySumMb / memorySamples, memorySamples,
			byRegion, byProfile, topRuntimes, evacuated, load(all), cobertura,
			throughput(all, now), health(all, now, cobertura));
	}

	/**
	 * Cada tipo de trabalho da rede e quantos nós podem executá-lo <b>agora</b> — o §8.4 do brief
	 * ("o painel deve mostrar como os nós trabalham juntos").
	 *
	 * <p>A pergunta que isto responde não está em nenhum outro lugar do painel: <b>a rede tem
	 * alguém para fazer cada tipo de trabalho?</b> {@code byProfile} conta perfis declarados, o que
	 * não é a mesma coisa — um nó {@code NODE_MEDIA} em quarentena, de região evacuada ou sem vaga
	 * continua contado no perfil e não executa nada. Hoje essa descoberta custava abrir nó por nó
	 * até achar que nenhum aceitava {@code MEDIA_TRANSCODE}.</p>
	 *
	 * <p>"Pode agora" é a régua do despacho: {@code ACTIVE}, vivo na mesma janela do scheduler, sem
	 * quarentena, fora de região evacuada e com vaga. A fleet é pequena e cabe no teto da listagem;
	 * acima dele, a lista diz que está olhando um recorte.</p>
	 *
	 * <p>{@code uncovered} é a lista dos sem capacidade — é o que a tela pinta de vermelho. Lista
	 * vazia significa cobertura completa, e não "não medi": toda a enumeração é varrida, inclusive
	 * os tipos que nenhum perfil cobre.</p>
	 */
	private List<WorkCoverage> workTypes(List<NetworkNode> all, List<String> evacuated, Instant now) {
		List<WorkCoverage> result = new java.util.ArrayList<>();
		for (TaskType type : TaskType.values()) {
			int capable = 0;
			for (NetworkNode node : all) {
				if (node.status() != NodeStatus.ACTIVE || !node.alive(now, Duration.ofSeconds(STALE_SECONDS))
						|| node.quarantined(now) || NetworkRegions.outOfService(evacuated, node.region())
						|| !node.hasCapacity() || !type.allowedFor(node.profiles())) {
					continue;
				}
				capable++;
			}
			result.add(new WorkCoverage(type.name(), capable));
		}
		return List.copyOf(result);
	}

	/**
	 * Os números do topo do painel. {@code alive} e {@code degraded} só contam ACTIVE: nó pendente
	 * não está "indisponível" — nunca chegou a operar — e contar suspenso junto de degradado
	 * misturaria decisão administrativa com saúde medida.
	 *
	 * <p>{@code avgCpuPercent}/{@code avgMemoryMb} são médias dos reportes recentes com a contagem
	 * de amostras ao lado: média sem amostras é {@code null}, e média sem saber de quantos nós é
	 * média não diz nada. Capacidade livre é a mesma conta conservadora do modo: nó saturado,
	 * esgotado de memória, em quarentena ou de região evacuada não promete vaga que não vai
	 * cumprir.</p>
	 *
	 * <p>{@code outOfServiceRegions} é aditivo e é o que a tela precisa para explicar por que a
	 * capacidade livre caiu sem nenhum nó ter saído: a região está fora por decisão humana, e a
	 * lista das que estão é a diferença entre "o painel está errado" e "houve incidente".</p>
	 */
	public record Dashboard(int total, int active, int unavailable, int quarantined, int alive, int degraded,
			int declaredCpuCores, int declaredMemoryMb, int freeSlots, Double avgCpuPercent, int cpuSamples,
			Integer avgMemoryMb, int memorySamples, Map<String, Integer> byRegion, Map<String, Integer> byProfile,
			Map<String, Integer> runtimeVersions, List<String> outOfServiceRegions, Distribution load,
			List<WorkCoverage> workTypes, Throughput throughput, HealthGroup health) {
	}

	/**
	 * O throughput da rede agora — o §8.3 do brief ("throughput global").
	 *
	 * <p>Soma das taxas <b>medidas</b> de cada nó vivo neste instante, em MiB/s. Não é a soma dos
	 * acumulados do heartbeat: um total desde o boot somado à taxa não dá razão nenhuma, e a taxa é
	 * o que a pergunta do §8.2 pode responder. Só entra quem está {@code ACTIVE} e vivo na janela do
	 * scheduler: um nó que parou de bater coração há uma hora não tem taxa de agora, e o último
	 * número dele somaria tráfego que já é passado.</p>
	 *
	 * @param inMbps      soma das entradas medidas; {@code null} quando nenhum nó vivo tem taxa
	 * @param outMbps     soma das saídas medidas; o mesmo critério, campo a campo
	 * @param measuredNodes quantos nós vivos contribuíram com pelo menos uma das duas medidas
	 * @param measuredBytesSinceBoot soma do ACUMULADO desde o boot dos nós vivos (painel do
	 *        participante); {@code null} quando ninguém reportou — é outra pergunta, e não vira taxa
	 */
	public record Throughput(Double inMbps, Double outMbps, int measuredNodes, Long measuredBytesSinceBoot) {
	}

	/**
	 * A taxa de rede dos nós vivos. Cada campo se soma só sobre os nós que o mediram: um nó com a
	 * leitura do intervalo falha não empurra a soma para baixo com um zero que ninguém leu, do mesmo
	 * jeito que a média de CPU ignora quem não reportou CPU.
	 */
	private Throughput throughput(List<NetworkNode> all, Instant now) {
		double in = 0;
		double out = 0;
		int entradas = 0;
		int saidas = 0;
		java.util.Set<String> medida = new java.util.HashSet<>();
		long acumulado = 0;
		int comAcumulado = 0;
		for (NetworkNode node : all) {
			if (node.status() != NodeStatus.ACTIVE || !node.alive(now, Duration.ofSeconds(STALE_SECONDS))) {
				continue;
			}
			// O ponto do minuto corrente: a taxa de um minuto atrás não é "agora". O número exibido é
			// do minuto, e a série é do minuto; um nó cujo último ponto é anterior não soma.
			List<NodeObservabilityRepository.Sample> ultimo = samples.samples(node.id(), 1);
			if (ultimo.isEmpty()
					|| !ultimo.get(0).sampledAt().equals(now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES))) {
				continue;
			}
			NodeObservabilityRepository.Sample ponto = ultimo.get(0);
			if (ponto.netInMbps() != null) {
				in += ponto.netInMbps();
				entradas++;
			}
			if (ponto.netOutMbps() != null) {
				out += ponto.netOutMbps();
				saidas++;
			}
			if (ponto.netInMbps() != null || ponto.netOutMbps() != null) {
				medida.add(node.id());
			}
			if (node.observedBytesInMb() != null) {
				acumulado += node.observedBytesInMb();
				comAcumulado++;
			}
		}
		return new Throughput(entradas == 0 ? null : Math.round(in * 100) / 100.0,
			saidas == 0 ? null : Math.round(out * 100) / 100.0, medida.size(),
			comAcumulado == 0 ? null : acumulado);
	}

	/** O atraso do nó é uma consulta por tipo de conteúdo; acima do teto fica não medido. */
	static final int MAX_HEALTH_BEHIND_NODES = 50;

	/** O painel mostra os alertas mais urgentes; {@code alertsTotal} diz quantos existem. */
	static final int MAX_HEALTH_ALERTS = 50;

	/**
	 * As quatro dimensões de saúde do §8.8 sobre a rede <b>inteira</b> — o topo do painel.
	 *
	 * <p>A dimensão já existia por nó ({@link com.socialcoisa.rede.network.domain.NodeHealth}, na
	 * página do nó). O que faltava era o agregado: quantos nós atendem em cada dimensão e quais
	 * estão {@code FAILING}. A pergunta do operador — "a máquina está de pé? o runtime se
	 * identifica? a aplicação serve? a rede a aceita?" — só tem resposta de rede se as quatro forem
	 * contadas separadas; um "verde" único afunda o nó que perdeu heartbeat junto do nó suspenso por
	 * decisão administrativa.</p>
	 *
	 * <p><b>O que entra.</b> Todo nó listado, menos os {@code PENDING_APPROVAL}: esse não está fora
	 * de saúde, está esperando aprovação, e contá-lo como {@code FAILING} faria fila de aprovação
	 * parecer incidente — é o mesmo recorte de {@code unavailable} no topo.</p>
	 *
	 * <p><b>O atraso de sincronização.</b> A dimensão de aplicação depende dele, e a conta é uma
	 * consulta por tipo de conteúdo. O quadro mede até {@value #MAX_HEALTH_BEHIND_NODES} nós por
	 * ciclo; acima disso, {@code measured=false} diz que o atraso de alguém não foi consultado, em
	 * vez de afirmar "em dia" sobre quem ninguém olhou.</p>
	 *
	 * <p><b>Os alertas.</b> Um por nó, o mais urgente, pela regra do domínio
	 * ({@link NodeHealth#alert}) — a ordem é estável ({@code severidade}, depois o id), para o
	 * recorte exibido não depender da ordem em que a listagem veio do banco. {@code alertsTotal}
	 * é quantos existem, mesmo quando a lista vem cortada.</p>
	 */
	private HealthGroup health(List<NetworkNode> all, Instant now, List<WorkCoverage> cobertura) {
		Map<String, Integer> ok = new LinkedHashMap<>();
		Map<String, Integer> degraded = new LinkedHashMap<>();
		Map<String, Integer> failing = new LinkedHashMap<>();
		Map<String, Integer> byStatus = new TreeMap<>();
		List<HealthAlert> alertas = new java.util.ArrayList<>();
		int contados = 0;
		int medidos = 0;
		for (NodeHealth.Dimension d : NodeHealth.Dimension.values()) {
			ok.put(d.name(), 0);
			degraded.put(d.name(), 0);
			failing.put(d.name(), 0);
		}
		for (NetworkNode node : all) {
			byStatus.merge(node.status().name(), 1, Integer::sum);
			if (node.status() == NodeStatus.PENDING_APPROVAL) {
				continue;
			}
			contados++;
			Long behind = behindOf(node.id(), medidos < MAX_HEALTH_BEHIND_NODES);
			if (behind != null) {
				medidos++;
			}
			NodeHealth painel = NodeHealth.of(node, behind == null ? 0 : behind, now);
			for (NodeHealth.Dimension d : NodeHealth.Dimension.values()) {
				NodeHealth.State estado = d.of(painel);
				if (estado == NodeHealth.State.OK) {
					ok.merge(d.name(), 1, Integer::sum);
				}
				else if (estado == NodeHealth.State.DEGRADED) {
					degraded.merge(d.name(), 1, Integer::sum);
				}
				else if (estado == NodeHealth.State.FAILING) {
					failing.merge(d.name(), 1, Integer::sum);
				}
			}
			NodeHealth.Alert alerta = NodeHealth.alert(painel, node, now, behind == null ? 0 : behind);
			if (alerta != null) {
				alertas.add(new HealthAlert(node.id(), node.displayName(), alerta.dimension(), alerta.code(),
					alerta.reason()));
			}
		}
		// O trabalho que o Control Plane enfileira sem que nó nenhum possa executar (§2.2/§8.8): a fila
		// é o número; o alerta diz QUAL tipo e POR QUE ele fica no Master. Só vale com pendente — sem
		// trabalho, "ninguém pode fazer" não é incidente, é um tipo desligado.
		for (WorkCoverage tipo : cobertura) {
			if (tipo.capable() != 0) {
				continue;
			}
			long pendentesTipo = tasks.countPendingByType(tipo.type());
			if (pendentesTipo == 0) {
				continue;
			}
			long pendentes = tasks.countByStatus(TaskStatus.PENDING);
			alertas.add(new HealthAlert(null, null, NodeHealth.Dimension.APPLICATION, "WORK_NO_TARGET",
				pendentesTipo + " tarefa(s) " + tipo.type() + " pendentes e nenhum nó capaz de executá-las"
					+ (pendentes > pendentesTipo ? " (" + pendentes + " pendentes na rede)" : "")));
		}
		// O alerta da rede não tem nó: um comparador com `HealthAlert::nodeId` estoura em NPE no
		// primeiro alerta de rede que aparece ao lado de um de nó. O nulo ordena antes, e o desempate
		// final é o código — dois alertas de rede podem existir (um por tipo sem quem faça).
		alertas.sort(Comparator.comparingInt((HealthAlert a) -> severidade(a.code()))
			.thenComparing(HealthAlert::nodeId, Comparator.nullsFirst(Comparator.naturalOrder()))
			.thenComparing(HealthAlert::code));
		return new HealthGroup(contados, byStatus, ok, degraded, failing, medidos == contados,
			List.copyOf(alertas.subList(0, Math.min(alertas.size(), MAX_HEALTH_ALERTS))), alertas.size());
	}

	/**
	 * O atraso de sincronização do nó — agregados em versão anterior à da origem. {@code null} é
	 * "não consultei" (acima do teto), nunca "está em dia": a dimensão de aplicação afirma saúde a
	 * partir dele.
	 */
	private Long behindOf(String nodeId, boolean medir) {
		if (!medir) {
			return null;
		}
		long behind = 0;
		for (ContentKind kind : ContentKind.values()) {
			behind += versions.countBehind(nodeId, kind);
		}
		return behind;
	}

	/**
	 * A urgência do código, para a lista de alertas: o que tira o nó da rede vem antes do que o faz
	 * servir pior. Código que não está aqui cai no fim, e o teste de ordem obriga a classificá-lo.
	 */
	static int severidade(String code) {
		return switch (code) {
			case "NODE_REVOKED", "NODE_SUSPENDED", "NODE_SILENT" -> 0;
			case "NODE_QUARANTINED", "NODE_NO_WORK" -> 1;
			case "NODE_FAILING", "NODE_BEHIND", "WORK_NO_TARGET" -> 2;
			case "NODE_SATURATED", "NODE_MEMORY_FULL", "NODE_FULL" -> 3;
			default -> 4;
		};
	}

	private static long segundosDesde(NetworkNode node, Instant now) {
		return node.lastSeenAt() == null ? -1 : Math.max(0, Duration.between(node.lastSeenAt(), now).getSeconds());
	}

	/**
	 * As quatro dimensões contadas na rede e os alertas do §8.8.
	 *
	 * @param countedNodes  nós no quadro (todos, menos {@code PENDING_APPROVAL})
	 * @param byStatus      quantos nós em cada status do catálogo, para a tela explicar a diferença
	 * @param ok            por dimensão ({@code HOST}, {@code RUNTIME}, {@code APPLICATION},
	 *                      {@code NETWORK}), quantos nós estão {@code OK}
	 * @param degraded      idem, quantos atendem com defeito
	 * @param failing       idem, quantos não atendem
	 * @param measured      {@code true} quando o atraso de <b>todos</b> os nós do quadro foi consultado
	 * @param alerts        os alertas mais urgentes (recorte de {@code alertsTotal})
	 * @param alertsTotal   quantos alertas existem no quadro inteiro
	 */
	public record HealthGroup(int countedNodes, Map<String, Integer> byStatus, Map<String, Integer> ok,
			Map<String, Integer> degraded, Map<String, Integer> failing, boolean measured, List<HealthAlert> alerts,
			int alertsTotal) {
	}

	/**
	 * @param nodeId    o nó do alerta; {@code null} quando o alerta é da <b>rede</b>, não de um nó
	 *                  (ex.: {@code WORK_NO_TARGET} — ninguém pode executar o que foi enfileirado)
	 * @param nodeName  o nome que o operador vê; idem, nulo no alerta de rede
	 * @param dimension dimensão da saúde onde o fato aparece
	 * @param code      código estável ({@code NODE_SILENT}, {@code NODE_SATURATED}, …)
	 * @param reason    a frase com o número e o porquê
	 */
	public record HealthAlert(String nodeId, String nodeName, NodeHealth.Dimension dimension, String code,
			String reason) {
	}

	/**
	 * Quantos nós podem executar um tipo de trabalho agora.
	 *
	 * @param type     o tipo de trabalho da rede ({@link com.socialcoisa.rede.network.domain.TaskType})
	 * @param capable  nós {@code ACTIVE}, vivos, sem quarentena, fora de região evacuada e com vaga
	 *                 que aceitam este tipo; {@code 0} = a rede não tem quem o faça
	 */
	public record WorkCoverage(String type, int capable) {
	}

	/**
	 * Como o trabalho está distribuído entre os nós — o §8.4 do brief (cooperativismo global).
	 *
	 * <p>A pergunta que estes números respondem: <b>a rede está distribuindo o processamento ou o
	 * Master absorve o que as réplicas poderiam fazer?</b> Por isso cada nó vem com o que executou,
	 * o que falhou e quantas tarefas estão na mão dele <b>agora</b> — e o agregado traz
	 * {@code concentratedShare}, a fatia do nó que mais executou.</p>
	 *
	 * <p>Quando não há o que concluir, o relatório diz isso em vez de mostrar zero: sem tarefa
	 * concluída, {@code concentratedShare} é {@code null} — "nenhum trabalho" e "todo o trabalho num
	 * nó só" seriam lidos iguais se ambos fossem 0 ou 1. Do mesmo jeito, {@code singleNode} só é
	 * verdadeiro com pelo menos duas réplicas ativas: com uma só, a fatia de 100% não é concentração,
	 * é a única opção.</p>
	 *
	 * @param nodes          um registro por nó listado no painel, incluindo os que nunca executaram
	 * @param totalDone      tarefas concluídas em toda a rede (soma do que foi contado por nó)
	 * @param totalFailed    falhas definitivas por nó (idem)
	 * @param unassigned     concluídas sem nó no registro — contam para o total, não para um nó
	 * @param concentratedShare fatia do nó com mais conclusões; {@code null} sem conclusão alguma
	 * @param singleNode     a maior fatia é de um nó com 2+ réplicas ativas: a distribuição não ocorre
	 */
	public record Distribution(List<NodeLoad> nodes, long totalDone, long totalFailed, long unassigned,
			Double concentratedShare, boolean singleNode) {
	}

	/**
	 * O que um nó executou. {@code runningNow} é o último reportado no heartbeat (campo cru) e pode
	 * ser {@code null} — runtime antigo não manda, e nulo não é "zero tarefas rodando".
	 */
	public record NodeLoad(String nodeId, String displayName, String status, long done, long failed,
			Integer runningNow) {
	}

	/**
	 * Monta a distribuição. Só os nós que o painel lista entram: um nó fora do teto de listagem não
	 * é "nó com zero tarefas", e somá-lo esconderia trabalho que existe.
	 */
	private Distribution load(List<NetworkNode> all) {
		List<String> ids = all.stream().map(NetworkNode::id).toList();
		Map<String, Long> done = ids.isEmpty() ? Map.of() : tasks.countDoneByNode(ids);
		Map<String, Long> failed = ids.isEmpty() ? Map.of() : tasks.countFailedByNode(ids);
		long totalDone = 0;
		long totalFailed = 0;
		List<NodeLoad> perNode = new java.util.ArrayList<>(all.size());
		for (NetworkNode node : all) {
			long concluidas = done.getOrDefault(node.id(), 0L);
			totalDone += concluidas;
			long falhas = failed.getOrDefault(node.id(), 0L);
			totalFailed += falhas;
			// observedRunningTasks cru, não runningTasks(): o getter converte nulo em zero, e "não
			// reportou" não é "nenhuma tarefa rodando" — na tela os dois casos são decisões diferentes.
			perNode.add(new NodeLoad(node.id(), node.displayName(), node.status().name(), concluidas, falhas,
				node.observedRunningTasks()));
		}
		// Concluídas que não caíram em nenhum nó: o total da rede é do catálogo inteiro, e a
		// diferença para o que foi somado por nó é justamente o que não tem dono no registro.
		long todasConcluidas = tasks.countByStatus(TaskStatus.DONE);
		long semNo = Math.max(0, todasConcluidas - totalDone);
		long maior = perNode.stream().mapToLong(NodeLoad::done).max().orElse(0);
		// Sem conclusão alguma, "fatia" não existe: zero sobre zero não é percentual.
		Double fatia = totalDone == 0 ? null : Math.round((maior * 1000.0 / totalDone)) / 10.0;
		long ativas = perNode.stream().filter(n -> "ACTIVE".equals(n.status())).count();
		boolean concentrado = fatia != null && ativas >= 2 && maior > 0 && maior == totalDone;
		return new Distribution(List.copyOf(perNode), todasConcluidas, totalFailed, semNo, fatia, concentrado);
	}
}
