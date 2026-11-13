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

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkRegions;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;

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
	private final Clock clock;

	NetworkDashboardService(NetworkNodeRepository nodes, NetworkRegionService regions, Clock clock) {
		this.nodes = nodes;
		this.regions = regions;
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

		return new Dashboard(total, active, unavailable, quarantined, alive, degraded, declaredCores,
			declaredMemoryMb, freeSlots, cpuSamples == 0 ? null : cpuSum / cpuSamples,
			cpuSamples, memorySamples == 0 ? null : memorySumMb / memorySamples, memorySamples,
			byRegion, byProfile, topRuntimes, evacuated);
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
			Map<String, Integer> runtimeVersions, List<String> outOfServiceRegions) {
	}
}
