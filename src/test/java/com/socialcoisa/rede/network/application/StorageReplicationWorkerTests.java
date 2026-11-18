package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
 * O relógio da replicação (fase 3): designa a lacuna sozinho, e solta a designação que nunca chegou.
 *
 * <p>O que estes testes seguram:</p>
 * <ul>
 * <li><b>designação vencida vira {@code GONE}</b> — é o que devolve a curabilidade ao operador: sem
 * isso {@code temDesignacaoPendente} tratava a pendência eterna como dona da lacuna;</li>
 * <li><b>designação nova NÃO é solta</b> — o nó ainda pode confirmar, e a mutação que solta tudo
 * aparece aqui;</li>
 * <li><b>o despacho acontece</b> — designar sem enfileirar {@code OBJECT_PUT} deixaria a cópia
 * esperando um nó que a puxasse por conta própria, que é o buraco que o dispatcher fechou;</li>
 * <li><b>desligado não faz nada</b> — o padrão de fábrica é não gerar tráfego de corpo.</li>
 * </ul>
 */
class StorageReplicationWorkerTests {

	private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

	private final List<StoragePlacement> copias = new ArrayList<>();
	private final List<StorageObject> objetos = new ArrayList<>();
	private final List<NetworkNode> candidatos = new ArrayList<>();
	private final List<String> despachados = new ArrayList<>();
	private StorageHealer healer;
	private StorageReplicationWorker worker;

	@BeforeEach
	void setUp() {
		StorageCatalogRepository catalog = catalogo();
		NetworkNodeRepository nodes = mock(NetworkNodeRepository.class);
		// Respondido no momento da chamada: `thenReturn` guardaria a lista vazia de quando o `setUp`
		// rodou, e os nós que o teste adiciona depois nunca apareceriam.
		when(nodes.findCandidates(any(), anyInt())).thenAnswer(inv -> new ArrayList<>(candidatos));
		when(nodes.findById(anyString())).thenAnswer(inv -> candidatos.stream()
			.filter(n -> n.id().equals(inv.getArgument(0))).findFirst());

		StoragePlacementService placement = new StoragePlacementService(nodes, catalog, 2, 90, 30);
		AuditTrail audit = mock(AuditTrail.class);
		StorageScrubber scrubber = new StorageScrubber(catalog, placement, audit, Clock.fixed(NOW, ZoneOffset.UTC),
			null, null, nodes);
		PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
		when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		healer = new StorageHealer(scrubber, placement, catalog, audit, tx,
			Clock.fixed(NOW, ZoneOffset.UTC));
		StorageReplicationDispatcher dispatcher = mock(StorageReplicationDispatcher.class);
		when(dispatcher.dispatch(anyString())).thenAnswer(inv -> {
			String objectId = inv.getArgument(0);
			despachados.add(objectId);
			return new StorageReplicationDispatcher.Report(1, List.of(), List.of());
		});
		worker = new StorageReplicationWorker(healer, dispatcher, catalog, Clock.fixed(NOW, ZoneOffset.UTC), true, 60);
	}

	@Test
	void designacao_vencida_vira_gone_e_a_lacuna_volta_a_ser_curavel() {
		objetos.add(objeto("sob_a"));
		copias.add(new StoragePlacement("sob_a", 0, "control-plane", 10L, "hash", StoragePlacement.State.OK,
			NOW.minusSeconds(7200)));
		// Designada há três horas e nunca confirmada.
		copias.add(new StoragePlacement("sob_a", 1, "node_antigo", 10L, "hash",
			StoragePlacement.State.PENDING_REMOTE, NOW.minusSeconds(10800)));
		candidatos.add(no("node_novo"));

		var resultado = worker.tick(NOW);

		assertThat(resultado.released()).isEqualTo(1);
		assertThat(copias.stream().filter(c -> c.replica() == 1).findFirst().orElseThrow().state())
			.isEqualTo(StoragePlacement.State.GONE);
		// Solta a vencida e designa de novo — a lacuna deixou de estar "a caminho".
		assertThat(resultado.designated()).isEqualTo(1);
		assertThat(copias).anySatisfy(c -> {
			assertThat(c.state()).isEqualTo(StoragePlacement.State.PENDING_REMOTE);
			assertThat(c.nodeId()).isEqualTo("node_novo");
			assertThat(c.replica()).isEqualTo(2);
		});
		assertThat(despachados).containsExactly("sob_a");
	}

	@Test
	void designacao_recente_nao_e_solta() {
		objetos.add(objeto("sob_b"));
		copias.add(new StoragePlacement("sob_b", 0, "control-plane", 10L, "hash", StoragePlacement.State.OK,
			NOW.minusSeconds(600)));
		copias.add(new StoragePlacement("sob_b", 1, "node_x", 10L, "hash", StoragePlacement.State.PENDING_REMOTE,
			NOW.minusSeconds(300)));
		candidatos.add(no("node_y"));

		var resultado = worker.tick(NOW);

		assertThat(resultado.released()).isZero();
		assertThat(copias).hasSize(2);
		assertThat(copias).noneMatch(c -> c.state() == StoragePlacement.State.GONE);
		// A pendência tem dono: o healer relata ALREADY_DESIGNATED e não designa outra.
		assertThat(resultado.designated()).isZero();
		assertThat(despachados).isEmpty();
	}

	@Test
	void desligado_nao_solta_nem_designa() {
		objetos.add(objeto("sob_c"));
		copias.add(new StoragePlacement("sob_c", 0, "control-plane", 10L, "hash", StoragePlacement.State.OK,
			NOW.minusSeconds(7200)));
		copias.add(new StoragePlacement("sob_c", 1, "node_z", 10L, "hash", StoragePlacement.State.PENDING_REMOTE,
			NOW.minusSeconds(10800)));
		candidatos.add(no("node_w"));
		var desligado = new StorageReplicationWorker(healer, mock(StorageReplicationDispatcher.class), null,
			Clock.fixed(NOW, ZoneOffset.UTC), false, 60);

		// O agendador chama `run()`; desligado, ele não deve nem tocar no catálogo.
		desligado.run();

		assertThat(desligado.enabled()).isFalse();
		assertThat(copias).hasSize(2);
		assertThat(copias).noneMatch(c -> c.state() == StoragePlacement.State.GONE);
	}

	// ---------------------------------------------------------------- apoio

	private static StorageObject objeto(String id) {
		return StorageObject.current(id, "bkt", id + ".txt", "text/plain", 10L, "hash", StorageObjectState.STORED,
			"user_1", NOW.minusSeconds(86400), NOW.minusSeconds(86000), null);
	}

	private static NetworkNode no(String id) {
		// Nó de verdade, não mock: `alive` e `quarantined` comparam instantes, e um mock que devolve
		// null para eles faz o nó parecer morto — o healer recusaria o candidato por um motivo que o
		// teste não está exercitando.
		// Mesma forma do StorageHealerTests: disco cedido (102400) e livre medido (5000) passando os
		// dois pisos (folga cedida >= 1024 MB e livre >= 1024 MB). Um mock aqui é armadilha: `alive`
		// e `quarantined` comparam instantes, e null no mock faz o nó parecer morto — o healer
		// recusaria por um motivo que o teste não está exercitando.
		return new NetworkNode(id, "no-" + id, "user_dono", NodeStatus.ACTIVE,
			java.util.Set.of(NodeProfile.NODE_STORAGE), null, "BR", "1.0.0", NOW, 4, 4096, 102400, 100, null, null,
			null, null, 5000L, 0, null, null);
	}

	/**
	 * O catálogo destes testes: Mockito sobre as três operações que importam, com as listas do
	 * teste por trás. Implementar a porta inteira aqui seria 30 métodos de ruído para quatro perguntas.
	 */
	private StorageCatalogRepository catalogo() {
		StorageCatalogRepository catalog = mock(StorageCatalogRepository.class);
		when(catalog.listAll(any(), any(Integer.class))).thenAnswer(inv -> {
			String afterKey = inv.getArgument(0);
			return objetos.stream().filter(o -> afterKey == null || o.key().compareTo(afterKey) > 0)
				.sorted(java.util.Comparator.comparing(StorageObject::bucket).thenComparing(StorageObject::key))
				.limit((Integer) inv.getArgument(1))
				.toList();
		});
		when(catalog.findById(anyString())).thenAnswer(inv -> objetos.stream()
			.filter(o -> o.id().equals(inv.getArgument(0))).findFirst());
		when(catalog.placementsOnNode(anyString())).thenReturn(List.of());
		when(catalog.copies(anyString())).thenAnswer(inv -> copias.stream()
			.filter(c -> c.objectId().equals(inv.getArgument(0)))
			.sorted(java.util.Comparator.comparingInt(StoragePlacement::replica)).toList());
		when(catalog.placements(anyString())).thenAnswer(inv -> copias.stream()
			.filter(c -> c.objectId().equals(inv.getArgument(0)) && c.state() == StoragePlacement.State.OK)
			.toList());
		// A gravação é o que os testes observam: a lista é a fonte da verdade, sem banco.
		org.mockito.Mockito.doAnswer(inv -> {
			StoragePlacement p = inv.getArgument(0);
			copias.removeIf(c -> c.objectId().equals(p.objectId()) && c.replica() == p.replica());
			copias.add(p);
			return null;
		}).when(catalog).savePlacement(any());
		return catalog;
	}

}