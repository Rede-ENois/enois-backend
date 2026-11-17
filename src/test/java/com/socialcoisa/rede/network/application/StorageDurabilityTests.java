package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * O §9 do brief pede que o nível de durabilidade <b>efetivamente oferecido</b> seja exibido, e que
 * um storage node sozinho não seja apresentado como redundante.
 *
 * <p>A contagem de cópias não responde isso: <b>duas cópias na mesma região</b> e <b>duas cópias em
 * regiões distintas</b> têm o mesmo número e redundância completamente diferente. Todos os casos
 * abaixo usam <b>o mesmo número de cópias</b> e só a geografia muda — se a classificação olhasse o
 * número, os testes passariam todos pela mesma razão e não provariam nada.</p>
 */
class StorageDurabilityTests {

	private static final Instant NOW = Instant.parse("2026-11-15T18:00:00Z");

	private StorageCatalogRepository catalog;
	private NetworkNodeRepository nodes;
	private StorageScrubber scrubber;

	@BeforeEach
	void setUp() {
		catalog = mock(StorageCatalogRepository.class);
		nodes = mock(NetworkNodeRepository.class);
		AuditTrail audit = mock(AuditTrail.class);
		StorageCatalogService bodies = mock(StorageCatalogService.class);
		scrubber = new StorageScrubber(catalog, stubPlacement(3), audit, Clock.fixed(NOW, ZoneOffset.UTC), bodies,
			null, nodes);
	}

	/** A colocação só define o alvo; a classificação não a consulta além disso. */
	private static StoragePlacementService stubPlacement(int replicas) {
		StoragePlacementService placement = mock(StoragePlacementService.class);
		when(placement.targetReplicas()).thenReturn(replicas);
		return placement;
	}

	private void regiao(String nodeId, String region) {
		when(nodes.findById(nodeId)).thenReturn(Optional.of(node(nodeId, region)));
	}

	private static NetworkNode node(String id, String region) {
		return new NetworkNode(id, id, "owner", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE), region, "BR",
			"0.1.0", NOW, 4, 8192, 102400, 100, null, null, null, null, 40960L, null, null, 0, null, null);
	}

	private static StoragePlacement copia(int replica, String nodeId) {
		return new StoragePlacement("sob_1", replica, nodeId, 10, "abc", StoragePlacement.State.OK, NOW);
	}

	/**
	 * Duas cópias de nó, <b>mesma região</b>: a contagem é 3 de 3 se a réplica 0 estiver junto, mas
	 * as duas externas caem quando a região cai. É o caso que o número de cópias esconde.
	 */
	@Test
	void duasCopiasNaMesmaRegiaoSaoUmUnicoDominio() {
		regiao("nd_a", "sudeste");
		regiao("nd_b", "SUDESTE");

		// Réplica 0 (Control Plane) + 2 nós da mesma região: a asserção olha só as externas.
		StorageScrubber.Durability nivel = scrubber.durability("sob_1", List.of(copia(1, "nd_a"), copia(2, "nd_b")), 3);

		assertThat(nivel).isEqualTo(StorageScrubber.Durability.SINGLE_DOMAIN);
	}

	/** As MESMAS duas cópias, em regiões distintas: mesmo número, durabilidade diferente. */
	@Test
	void duasCopiasEmRegioesDistintasJaSaoDominiosSeparados() {
		regiao("nd_a", "sudeste");
		regiao("nd_b", "sul");

		StorageScrubber.Durability nivel = scrubber.durability("sob_1", List.of(copia(1, "nd_a"), copia(2, "nd_b")), 2);

		assertThat(nivel).isEqualTo(StorageScrubber.Durability.CROSS_REGION);
	}

	/** Menos cópias que o pedido, mas em domínios distintos: parcial, não "ok" e não "single". */
	@Test
	void menosCopiasQueOPedidoMasEmDominiosDistintosEhParcial() {
		regiao("nd_a", "sudeste");
		regiao("nd_b", "sul");

		StorageScrubber.Durability nivel = scrubber.durability("sob_1", List.of(copia(1, "nd_a")), 2);

		// Uma cópia só é SINGLE_DOMAIN mesmo com o alvo maior: não há redundância alguma.
		assertThat(nivel).isEqualTo(StorageScrubber.Durability.SINGLE_DOMAIN);
		assertThat(scrubber.durability("sob_1", List.of(copia(1, "nd_a"), copia(2, "nd_b")), 3))
			.isEqualTo(StorageScrubber.Durability.CROSS_REGION_PARTIAL);
	}

	/**
	 * Nó sem região é domínio <b>desconhecido</b>: não se funde com outro desconhecido (a regra de
	 * {@code NetworkRegions}) porque a rede não sabe se são o mesmo lugar. Fundir daria
	 * SINGLE_DOMAIN onde há duas máquinas distintas.
	 */
	@Test
	void nosSemRegiaoSaoDominiosDistintosEntreSi() {
		regiao("nd_a", null);
		regiao("nd_b", null);

		assertThat(scrubber.durability("sob_1", List.of(copia(1, "nd_a"), copia(2, "nd_b")), 2))
			.isEqualTo(StorageScrubber.Durability.CROSS_REGION);
	}

	/**
	 * A réplica 0 é o Control Plane, e ele é um domínio próprio: uma cópia no CP sobrevive à queda
	 * de qualquer storage node, e uma no nó sobrevive à queda do CP. Contá-las como o mesmo domínio
	 * diria "sem redundância" para todo objeto que tem cópia nos dois.
	 */
	@Test
	void aReplicaZeroEhUmDominioProprio() {
		// O CP ESTÁ no cadastro como nó, com a mesma região do nd_a — situação real: o processo do
		// Control Plane também pode ser um nó de storage registrado. Sem o domínio próprio, réplica 0
		// e nd_a caem em "region:sudeste", e o objeto com cópia nos dois seria "sem redundância".
		regiao("nd_ctrl", "sudeste");
		regiao("nd_a", "sudeste");

		assertThat(scrubber.durability("sob_1", List.of(copia(0, "nd_ctrl"), copia(1, "nd_a")), 2))
			.isEqualTo(StorageScrubber.Durability.CROSS_REGION);
	}

	/**
	 * Nó que o cadastro não devolve não pode ser tratado como "mesma região" de outro: o que a rede
	 * sabe é que ele tem cópia. Conta como desconhecido e distinto.
	 */
	@Test
	void noForaDoCadastroNaoSeFundeComOutro() {
		regiao("nd_a", "sudeste");
		when(nodes.findById(anyString())).thenReturn(Optional.empty());

		assertThat(scrubber.durability("sob_1", List.of(copia(1, "nd_a"), copia(2, "nd_fantasma")), 2))
			.isEqualTo(StorageScrubber.Durability.CROSS_REGION);
	}

	/**
	 * O painel conta por nível o que a varredura viu. A soma tem de fechar com os objetos servíveis
	 * varridos — um objeto sem nível seria um objeto que o painel não sabe classificar.
	 */
	@Test
	void aVarreduraContaObjetosPorNivelDeDurabilidade() {
		StorageObject objeto = new StorageObject("sob_1", "fotos", "a.jpg", "image/jpeg", 10L, "abc",
			StorageObjectState.STORED, "user_1", NOW, NOW, null);
		StorageObject outro = new StorageObject("sob_2", "fotos", "b.jpg", "image/jpeg", 10L, "def",
			StorageObjectState.STORED, "user_1", NOW, NOW, null);
		when(catalog.listAll(any(), anyInt())).thenReturn(List.of(objeto));
		when(catalog.copies("sob_1")).thenReturn(List.of(copia(1, "nd_a"), copia(2, "nd_b")));
		regiao("nd_a", "sudeste");
		regiao("nd_b", "sul");

		StorageScrubber.Report report = scrubber.scan(null, 10);

		// Um objeto, dois domínios, alvo 3: parcial. O contador é o que o painel mostra.
		assertThat(report.byDurability()).containsExactly(entry("CROSS_REGION_PARTIAL", 1L));
		assertThat(report.objectsScanned()).isEqualTo(1);
		assertThat(outro.id()).isEqualTo("sob_2");
	}

	@Test
	void varredura_de_rede_vazia_nao_inventa_niveis() {
		assertThat(scrubber.scan(null, 10).byDurability()).isEmpty();
	}

}