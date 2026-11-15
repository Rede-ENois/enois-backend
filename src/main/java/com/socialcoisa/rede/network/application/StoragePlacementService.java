package com.socialcoisa.rede.network.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkRegions;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.network.domain.TaskType;

/**
 * Escolha do nó que recebe uma cópia do objeto.
 *
 * <p>A fase 1 gravou sempre no Control Plane (réplica 0). Esta fase acrescenta as réplicas 1..N-1
 * num nó de armazenamento real, e as regras de escolha são as que decidem se uma réplica <b>existe
 * de fato</b>:</p>
 *
 * <ul>
 * <li><b>Não replica no mesmo nó.</b> Duas cópias no mesmo disco não sobrevivem à perda do disco,
 * e a contagem diria 2. Excluir pelo {@code nodeId} é a garantia que a E/S do brief pede sob o
 * nome de "domínio de falha"; o domínio <b>maior</b> que o nó (host físico, operadora, rack) não
 * está cadastrado em lugar nenhum e inventá-lo seria deduzir.</li>
 * <li><b>Diversifica por região enquanto houver região distinta.</b> É o único domínio maior que o
 * nó que o cadastro <b>tem</b> e que o sistema já usa para decidir (evacuação, afinidade). Duas
 * cópias na mesma região caem juntas quando a região cai — o mesmo erro do nó repetido, um nível
 * acima. Região não declarada é domínio desconhecido: não é igual à de quem declarou, nem de outro
 * que também não declarou (a regra de {@code NetworkRegions}).</li>
 * <li><b>Não replica em nó que não está pronto agora.</b> Mesma régua do despacho de tarefa:
 * {@code ACTIVE}, heartbeat recente, perfil de armazenamento, sem quarentena. Nó que o heartbeat
 * ainda não confirmou pode estar com o processo morto.</li>
 * <li><b>Só replica o que não é conhecido como cheio.</b> Disco livre medido acima do piso e da
 * própria cópia, ou não medido (não saber não é saber que não cabe — a gravação falha alto e a
 * cópia fica {@code STALE}, não contada).</li>
 * <li><b>A escolhe o nó com mais folga</b>. Ordem estável, sem sorteio: o mesmo objeto, reenviado,
 * escolhe o mesmo conjunto enquanto a rede não mudar — e um conjunto que muda a cada retry daria
 * réplicas espalhadas e córpias órfãs.</li>
 * </ul>
 *
 * <p>O nó escolhido não é "aqui está o IP": ele é designado <b>no catálogo</b>, e recebe o objeto
 * buscando na própria URL do Control Plane, autenticado com a chave de nó. É o mesmo endereçamento
 * que o brief §17 permite — nenhum IP ou hostname de storage node é cadastrado em lugar nenhum.</p>
 */
@Service
public class StoragePlacementService {

	/** Teto da listagem de candidatos por seleção: o mesmo das demais escolhas do módulo. */
	private static final int MAX_CANDIDATES = 100;

	/** Quantas cópias o objeto deve ter no total, contando a do Control Plane (réplica 0). */
	private final int replicas;

	/** Janela de frescor do heartbeat, em segundos. */
	private final java.time.Duration nodeStaleAfter;

	private final NetworkNodeRepository nodes;
	private final StorageCatalogRepository catalog;

	StoragePlacementService(NetworkNodeRepository nodes, StorageCatalogRepository catalog,
			@Value("${rede.storage.replicas:2}") int replicas,
			@Value("${rede.network.node-stale-seconds:90}") int staleSeconds) {
		// Uma réplica é o comportamento da fase 1: sem cópia por nó, só a do Control Plane. Zero ou
		// negativo não faz sentido — objeto sem nenhuma cópia não é objeto —, então o piso é 1.
		this.replicas = Math.max(1, replicas);
		this.nodeStaleAfter = java.time.Duration.ofSeconds(Math.max(1, staleSeconds));
		this.nodes = nodes;
		this.catalog = catalog;
	}

	/** O número de cópias configurado, incluindo a do Control Plane. */
	public int targetReplicas() {
		return replicas;
	}

	/**
	 * Nós que devem receber cópias novas deste objeto, em ordem de folga — no máximo
	 * {@code targetReplicas - 1} (o Control Plane é a réplica 0).
	 *
	 * <p>Já têm cópia os nós que aparecem como {@code OK} na colocação: nó que perdeu a cópia
	 * ({@code STALE}/{@code GONE}) volta a ser candidato, que é o caso da retomada.</p>
	 */
	public List<NetworkNode> chooseReplicas(String objectId, long bytes, Instant now) {
		int needed = replicas - 1;
		if (needed <= 0) {
			return List.of();
		}
		var occupied = catalog.placements(objectId)
			.stream()
			.filter(placement -> placement.state() == StoragePlacement.State.OK)
			.map(StoragePlacement::nodeId)
			.toList();

		// Domínios que já têm cópia: nó ocupado e, para a região, a região dos nós ocupados. Só
		// conta a região de nós que o repositório devolveu — a colocação não guarda região, e ler
		// dela exigiria uma consulta por réplica.
		var allCandidates = nodes.findCandidates(now.minus(nodeStaleAfter), MAX_CANDIDATES);
		// A região já ocupada tem de ser lida dos nós que TÊM cópia -- antes de filtrá-los fora.
		// Filtrar primeiro deixava `takenRegions` vazio e a segunda cópia caía na mesma região.
		var takenRegions = allCandidates.stream()
			.filter(node -> occupied.contains(node.id()))
			.map(NetworkNode::region)
			.filter(region -> region != null && !region.isBlank())
			.map(NetworkRegions::normalize)
			.collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
		var ready = allCandidates.stream()
			.filter(node -> !occupied.contains(node.id()))
			.filter(node -> storageReady(node, bytes, now))
			.sorted(Comparator.comparingLong((NetworkNode node) -> headroomMb(node, bytes)).reversed()
				.thenComparing(NetworkNode::id))
			.toList();

		// Duas passadas sobre a MESMA ordem (mais folga primeiro): primeiro uma cópia por região
		// ainda não usada; se faltar nó, completa com as regiões repetidas. Recusar aqui deixaria o
		// objeto com menos redundância do que a rede permite por causa da topologia — a segunda
		// cópia na mesma região ainda é melhor que nenhuma.
		List<NetworkNode> chosen = new java.util.ArrayList<>(needed);
		for (NetworkNode node : ready) {
			if (chosen.size() >= needed) {
				break;
			}
			String region = domainOf(node);
			if (region.isEmpty() || !takenRegions.contains(region)) {
				chosen.add(node);
				if (!region.isEmpty()) {
					takenRegions.add(region);
				}
			}
		}
		if (chosen.size() < needed) {
			var picked = chosen.stream().map(NetworkNode::id).collect(java.util.stream.Collectors.toSet());
			for (NetworkNode node : ready) {
				if (chosen.size() >= needed) {
					break;
				}
				if (!picked.contains(node.id())) {
					chosen.add(node);
					picked.add(node.id());
				}
			}
		}
		return List.copyOf(chosen);
	}

	/**
	 * O domínio de falha de um nó, na forma comparável. Vazio = desconhecido: quem não declarou
	 * região não é igual a ninguém — nem a outro que também não declarou, nem a uma região
	 * declarada. Tratar dois vazios como "a mesma região" esconderia justamente o caso em que não
	 * há informação para afirmar que são distintos.
	 */
	private static String domainOf(NetworkNode node) {
		return NetworkRegions.normalize(node.region());
	}

	/** O primeiro nó que deve receber cópia nova, se houver. */
	public Optional<NetworkNode> chooseReplica(String objectId, long bytes, Instant now) {
		return chooseReplicas(objectId, bytes, now).stream().findFirst();
	}

	/**
	 * O nó pode receber a cópia <b>agora</b>: está pronto, tem o perfil, não está em quarentena e
	 * não é conhecido como sem espaço. A ordem dos filtros é a do despacho de tarefa, para que uma
	 * recusa aqui signifique o mesmo que significaria lá.
	 */
	private boolean storageReady(NetworkNode node, long bytes, Instant now) {
		return node.status() == NodeStatus.ACTIVE
			&& node.alive(now, nodeStaleAfter)
			&& !node.quarantined(now)
			&& TaskType.OBJECT_PUT.allowedFor(node.profiles())
			&& fitsDisk(node, bytes);
	}

	/**
	 * O disco medido comporta os bytes da cópia, com o mesmo piso de folga que
	 * {@code StorageCatalogService} aplica ao próprio disco. Sem medida, a cópia é permitida: não
	 * saber não é saber que não cabe, e a gravação falha alto quando falhar.
	 */
	private static boolean fitsDisk(NetworkNode node, long bytes) {
		Long free = node.observedDiskFreeMb();
		if (free == null) {
			return true;
		}
		long neededMb = (bytes / (1024 * 1024)) + 1;
		return free - neededMb >= StorageCatalogService.MIN_DISK_FREE_MB;
	}

	private static long headroomMb(NetworkNode node, long bytes) {
		Long free = node.observedDiskFreeMb();
		if (free == null) {
			return 0;
		}
		return free - (bytes / (1024 * 1024));
	}

}