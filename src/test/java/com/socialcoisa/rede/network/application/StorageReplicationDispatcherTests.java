package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.network.domain.StorageRebalance;
import tools.jackson.databind.ObjectMapper;

/**
 * O payload do {@code OBJECT_PUT} — o contrato que o nó executa.
 *
 * <p>O que se prova aqui é o teto de banda <b>por transferência</b> (brief §16). Antes, o
 * {@code bandwidthLimitMbps} do rebalanceamento era gravado na tabela e nunca chegava ao nó: o
 * runtime aplicava só a banda declarada, e o teto que o operador pediu não valia nada. O vínculo é
 * a réplica de <b>destino</b> — a transferência que constrói esta cópia, não a que sai dela.</p>
 */
class StorageReplicationDispatcherTests {

	private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
	private static final String SHA = "a".repeat(64);

	private StorageCatalogRepository catalog;
	private NetworkNodeRepository nodes;
	private NetworkTaskService tasks;
	private StorageReplicationDispatcher dispatcher;
	private NetworkTask task;

	@BeforeEach
	void setUp() {
		catalog = mock(StorageCatalogRepository.class);
		nodes = mock(NetworkNodeRepository.class);
		tasks = mock(NetworkTaskService.class);
		when(catalog.findById("sob_1")).thenReturn(Optional.of(StorageObject.current("sob_1", "fotos", "a.jpg",
			"image/jpeg", 1024L, SHA, StorageObjectState.STORED, "user_1", NOW, NOW, null)));
		when(catalog.copies("sob_1")).thenReturn(List.of(new StoragePlacement("sob_1", 2, "nd_destino", 1024L, SHA,
			StoragePlacement.State.PENDING_REMOTE, NOW)));
		NetworkNode no = new NetworkNode("nd_destino", "no-destino", "user_2", NodeStatus.ACTIVE,
			Set.of(NodeProfile.NODE_STORAGE), null, "BR", "1.0.0", NOW, 4, 4096, 102400, 100, null, null, null, null,
			5000L, 0, null, null);
		when(nodes.findById("nd_destino")).thenReturn(Optional.of(no));
		when(nodes.findCandidates(any(), any(Integer.class))).thenReturn(List.of(no));
		task = mock(NetworkTask.class);
		when(task.id()).thenReturn("tk_1");
		when(tasks.enqueue(any())).thenReturn(task);
		dispatcher = new StorageReplicationDispatcher(catalog, nodes, tasks, new ObjectMapper(),
			Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void o_teto_da_transferencia_vai_no_payload_do_object_put() {
		when(catalog.activeRebalances(any(Integer.class))).thenReturn(List.of(transferencia(2, 8)));

		dispatcher.dispatch("sob_1");

		String payload = payloadEnviado();
		assertThat(payload).contains("\"bandwidthLimitMbps\":8").contains("\"replica\":2")
			.contains("\"objectId\":\"sob_1\"");
	}

	@Test
	void sem_transferencia_o_payload_nao_declara_teto() {
		when(catalog.activeRebalances(any(Integer.class))).thenReturn(List.of());

		dispatcher.dispatch("sob_1");

		// Ausência de teto é "usa o do nó", nunca zero (que seria banda nenhuma).
		assertThat(payloadEnviado()).doesNotContain("bandwidthLimitMbps");
	}

	@Test
	void transferencia_de_outra_replica_do_mesmo_objeto_nao_define_o_teto_desta() {
		when(catalog.activeRebalances(any(Integer.class))).thenReturn(List.of(transferencia(1, 8)));

		dispatcher.dispatch("sob_1");

		assertThat(payloadEnviado()).doesNotContain("bandwidthLimitMbps");
	}

	private String payloadEnviado() {
		ArgumentCaptor<NetworkTaskService.EnqueueCommand> captor =
			ArgumentCaptor.forClass(NetworkTaskService.EnqueueCommand.class);
		verify(tasks).enqueue(captor.capture());
		return captor.getValue().payload();
	}

	private static StorageRebalance transferencia(int toReplica, int mbps) {
		return new StorageRebalance("reb_1", "sob_1", 0, "nd_origem", toReplica, "nd_destino",
			StorageRebalance.State.PENDING, null, 1024L, mbps, "admin_1", NOW, null);
	}
}