package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StoragePlacement;

/**
 * A escolha de réplica é o que decide se a fase 3 replica <b>de verdade</b> ou só aumenta um
 * contador.
 *
 * <p>O que estes testes fixam, e por quê:</p>
 * <ul>
 * <li><b>nunca duas cópias no mesmo nó</b> — dois arquivos no mesmo disco não sobrevivem à perda do
 * disco, e a contagem diria 2. É a parte de "domínio de falha" que dá para afirmar sem inventar
 * topologia;</li>
 * <li><b>não designa para nó que não está pronto</b> — nó sem heartbeat recente pode ter o processo
 * morto; designar para ele é a cópia que nunca chega;</li>
 * <li><b>não designa para nó conhecido como cheio</b>, e <b>designa para nó sem medida</b> — não
 * saber não é saber que não cabe;</li>
 * <li><b>escolhe pela folga</b> e de forma estável — ordem por disco livre decrescente e desempate
 * por id, para o mesmo objeto não espalhar cópias diferentes a cada reenvio.</li>
 * </ul>
 */
class StoragePlacementServiceTests {

	private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
	private static final long MB = 1024 * 1024;

	private NetworkNodeRepository nodes;
	private StorageCatalogRepository catalog;
	private StoragePlacementService service;

	@BeforeEach
	void setUp() {
		nodes = mock(NetworkNodeRepository.class);
		catalog = mock(StorageCatalogRepository.class);
		when(catalog.placements("sob_1")).thenReturn(List.of());
		service = new StoragePlacementService(nodes, catalog, 3, 90);
	}

	@Test
	void distribuiPelosNosProntosComMaisFolga() {
		candidates(storageNode("nd_a", 40960L), storageNode("nd_b", 81920L), storageNode("nd_c", 20480L));

		List<NetworkNode> chosen = service.chooseReplicas("sob_1", 0, NOW);

		// Dois porque o alvo é 3 e a réplica 0 é do Control Plane. Mais folga primeiro.
		assertThat(chosen).extracting(NetworkNode::id).containsExactly("nd_b", "nd_a");
	}

	@Test
	void nuncaDesignaDuasCopiasNoMesmoNo() {
		when(catalog.placements("sob_1"))
			.thenReturn(List.of(new StoragePlacement("sob_1", 0, "nd_a", 10, "abc", StoragePlacement.State.OK, NOW)));
		candidates(storageNode("nd_a", 40960L), storageNode("nd_b", 81920L), storageNode("nd_c", 20480L));

		List<NetworkNode> chosen = service.chooseReplicas("sob_1", 0, NOW);

		assertThat(chosen).extracting(NetworkNode::id).containsExactly("nd_b", "nd_c");
	}

	@Test
	void replicaQuePerdeuACopiaVoltaASerCandidata() {
		// A linha existe em STALE: o nó foi suspenso ou não confirmou. Ele pode receber de novo.
		when(catalog.placements("sob_1")).thenReturn(
			List.of(new StoragePlacement("sob_1", 1, "nd_a", 10, "abc", StoragePlacement.State.STALE, NOW)));
		candidates(storageNode("nd_a", 40960L), storageNode("nd_b", 81920L));

		List<NetworkNode> chosen = service.chooseReplicas("sob_1", 0, NOW);

		assertThat(chosen).extracting(NetworkNode::id).containsExactly("nd_b", "nd_a");
	}

	@Test
	void noQueNaoEstaProntoNaoRecebeCopia() {
		NetworkNode morto = nodeWith("nd_morto", NodeStatus.SUSPENDED, Set.of(NodeProfile.NODE_STORAGE), 40960L, NOW);
		NetworkNode semPerfil = nodeWith("nd_sem", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_WORKER), 40960L, NOW);
		NetworkNode semHeartbeat = nodeWith("nd_nunca", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE), 40960L,
			NOW.minusSeconds(600));
		NetworkNode precário = storageNode("nd_a", 40960L);
		candidates(morto, semPerfil, semHeartbeat, precário);

		assertThat(service.chooseReplicas("sob_1", 0, NOW)).extracting(NetworkNode::id).containsExactly("nd_a");
	}

	@Test
	void noComDiscoMedidoAbaixoDoPisoNaoRecebeCopia() {
		// 500 MB livres: não cabe o objeto mais a folga mínima. O nó declarou mais, mas o MEDIDO manda.
		candidates(storageNode("nd_cheio", 500L), storageNode("nd_a", 40960L));

		assertThat(service.chooseReplicas("sob_1", 0, NOW)).extracting(NetworkNode::id).containsExactly("nd_a");
	}

	@Test
	void noSemMedidaDeDiscoNaoEhRecusadoPorIsso() {
		List<NetworkNode> chosen = List.of();
		candidates(nodeWith("nd_sem_medida", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE), null, NOW));

		chosen = service.chooseReplicas("sob_1", 0, NOW);

		// Não medir não é estar cheio: recusar aqui tiraria todo nó não atualizado da replicação.
		assertThat(chosen).extracting(NetworkNode::id).containsExactly("nd_sem_medida");
	}

	@Test
	void umaCopiaNaoDesignaNada() {
		StoragePlacementService soControlPlane = new StoragePlacementService(nodes, catalog, 1, 90);
		candidates(storageNode("nd_a", 40960L));

		assertThat(soControlPlane.chooseReplicas("sob_1", 0, NOW)).isEmpty();
	}

	@Test
	void objetoGrandeDemaisParaAFolgaNaoEntra() {
		// 2 GiB de objeto contra 2,5 GiB livres: sobra 0,5 GiB, abaixo do piso de 1 GiB.
		candidates(storageNode("nd_a", 2560L), storageNode("nd_b", 102400L));

		assertThat(service.chooseReplicas("sob_1", 2L * 1024 * MB, NOW)).extracting(NetworkNode::id)
			.containsExactly("nd_b");
	}

	private void candidates(NetworkNode... found) {
		when(nodes.findCandidates(any(), anyInt())).thenReturn(List.of(found));
	}

	private static NetworkNode storageNode(String id, long diskFreeMb) {
		return nodeWith(id, NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE), diskFreeMb, NOW);
	}

	private static NetworkNode nodeWith(String id, NodeStatus status, Set<NodeProfile> profiles, Long diskFreeMb,
			Instant lastSeen) {
		return new NetworkNode(id, id, "owner", status, profiles, "sa-east-1", "BR", "0.1.0", lastSeen, 4, 8192,
			102400, 100, null, null, null, null, diskFreeMb, 0, null, null);
	}

}