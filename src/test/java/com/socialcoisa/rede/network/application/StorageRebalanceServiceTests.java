package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.network.domain.StorageRebalance;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Rebalanceamento do Storage (brief §16).
 *
 * <p>A frase que manda neste serviço é a última do trecho do brief: <b>nunca remover a última cópia
 * saudável antes de confirmar a persistência e integridade do destino</b>. Os testes abaixo são a
 * prova disso em cada borda:</p>
 *
 * <ul>
 * <li>mover a única cópia é <b>recusado</b> — não é rebalancear, é apostar o arquivo;</li>
 * <li>a cópia antiga <b>não sai</b> enquanto o destino não estiver {@code OK};</li>
 * <li>desfazer no meio <b>não toca</b> a antiga e recolhe a designação nova;</li>
 * <li>a designação nova nasce {@code PENDING_REMOTE}, nunca {@code OK} — quem confirma é o nó.</li>
 * </ul>
 */
class StorageRebalanceServiceTests {

	private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
	private static final String SHA = "a".repeat(64);

	private StorageCatalogRepository catalog;
	private StorageReplicationDispatcher dispatcher;
	private StorageRebalanceService service;

	@BeforeEach
	void setUp() {
		catalog = mock(StorageCatalogRepository.class);
		dispatcher = mock(StorageReplicationDispatcher.class);
		service = new StorageRebalanceService(catalog, dispatcher, mock(AuditTrail.class),
			Clock.fixed(NOW, ZoneOffset.UTC));
		when(catalog.findById("sob_1")).thenReturn(Optional.of(objeto()));
		when(dispatcher.node("nd_novo")).thenReturn(Optional.of(node("nd_novo", NodeStatus.ACTIVE)));
		when(dispatcher.canStore(any(), any())).thenReturn(true);
	}

	/** O caminho feliz: entra a nova, a antiga continua, e a réplica nova nasce pendente. */
	@Test
	void mover_designa_replica_nova_e_mantem_a_antiga_servindo() {
		when(catalog.copies("sob_1")).thenReturn(List.of(copia(0, "nd_cheio"), copia(1, "nd_outro")));

		StorageRebalance rebalance = service.move("admin", "sob_1", 1, "nd_novo", 5);

		assertThat(rebalance.toReplica()).isEqualTo(2);
		assertThat(rebalance.state()).isEqualTo(StorageRebalance.State.PENDING);
		assertThat(rebalance.bandwidthLimitMbps()).isEqualTo(5);
		// A designação nova é PENDING_REMOTE: dizer OK aqui seria afirmar cópia que ninguém viu.
		verify(catalog).savePlacement(org.mockito.ArgumentMatchers.argThat(
			p -> p.replica() == 2 && p.nodeId().equals("nd_novo") && p.state() == StoragePlacement.State.PENDING_REMOTE));
		// E a antiga não foi tocada.
		verify(catalog, never()).savePlacement(org.mockito.ArgumentMatchers.argThat(p -> p.replica() == 1));
	}

	/**
	 * A única cópia saudável não sai. É a regra do brief, e o motivo é simples: mover a única cópia
	 * transforma uma falha de transferência em perda do arquivo.
	 */
	@Test
	void mover_a_unica_copia_do_objeto_e_recusado() {
		when(catalog.copies("sob_1")).thenReturn(List.of(copia(0, "nd_cheio")));

		assertThatThrownBy(() -> service.move("admin", "sob_1", 0, "nd_novo", null))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "STORAGE_REBALANCE_LAST_COPY");
		verify(catalog, never()).saveRebalance(any());
	}

	/** Origem que não está saudável não serve de origem: o conteúdo dela não é confiável. */
	@Test
	void origem_nao_saudavel_nao_serve_de_origem() {
		when(catalog.copies("sob_1")).thenReturn(List.of(copia(0, "nd_cheio"),
			new StoragePlacement("sob_1", 1, "nd_ruim", 10L, SHA, StoragePlacement.State.STALE, NOW)));

		assertThatThrownBy(() -> service.move("admin", "sob_1", 1, "nd_novo", null))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "STORAGE_REPLICA_NOT_HEALTHY");
	}

	/** Duas linhas no mesmo nó não aliviam ninguém e desviam da conta de domínio de falha. */
	@Test
	void destino_que_ja_tem_copia_e_recusado() {
		when(catalog.copies("sob_1")).thenReturn(List.of(copia(0, "nd_cheio"), copia(1, "nd_novo")));

		assertThatThrownBy(() -> service.move("admin", "sob_1", 0, "nd_novo", null))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "STORAGE_REBALANCE_SAME_NODE");
	}

	/** Nó de destino inativo não recebe: designar seria criar transferência que nunca enfileira. */
	@Test
	void destino_inativo_e_recusado() {
		when(catalog.copies("sob_1")).thenReturn(List.of(copia(0, "nd_cheio"), copia(1, "nd_outro")));
		when(dispatcher.node("nd_novo")).thenReturn(Optional.of(node("nd_novo", NodeStatus.SUSPENDED)));
		when(dispatcher.canStore(any(), any())).thenReturn(false);

		assertThatThrownBy(() -> service.move("admin", "sob_1", 0, "nd_novo", null))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "STORAGE_REBALANCE_DEST_NOT_READY");
	}

	/** Clique duplo não abre duas transferências da mesma réplica. */
	@Test
	void transferencia_viva_da_mesma_replica_e_recusada() {
		when(catalog.copies("sob_1")).thenReturn(List.of(copia(0, "nd_cheio"), copia(1, "nd_outro")));
		when(catalog.activeRebalance("sob_1", 0)).thenReturn(Optional.of(new StorageRebalance("reb_1", "sob_1", 0,
			"nd_cheio", 2, "nd_novo", StorageRebalance.State.PENDING, null, 10L, 10, "admin", NOW, null)));

		assertThatThrownBy(() -> service.move("admin", "sob_1", 0, "nd_novo", null))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "STORAGE_REBALANCE_IN_PROGRESS");
	}

	/**
	 * O passo que o brief proíbe pular: <b>sem a confirmação do destino a cópia antiga não sai</b>.
	 * Aqui a réplica nova ainda é {@code PENDING_REMOTE} — o nó não terminou de gravar.
	 */
	@Test
	void completar_sem_confirmacao_do_destino_nao_remove_a_antiga() {
		when(catalog.findRebalance("reb_1")).thenReturn(Optional.of(new StorageRebalance("reb_1", "sob_1", 0,
			"nd_cheio", 2, "nd_novo", StorageRebalance.State.PENDING, null, 10L, 10, "admin", NOW, null)));
		when(catalog.copies("sob_1")).thenReturn(List.of(copia(0, "nd_cheio"), copia(1, "nd_outro"),
			new StoragePlacement("sob_1", 2, "nd_novo", 10L, SHA, StoragePlacement.State.PENDING_REMOTE, NOW)));

		assertThatThrownBy(() -> service.complete("admin", "reb_1"))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "STORAGE_REBALANCE_DEST_NOT_CONFIRMED");
		verify(catalog, never()).savePlacement(any());
		verify(catalog, never()).transitionRebalance(any(), any(), any(), any(), any());
	}

	/** Com o destino {@code OK}, a antiga vira {@code GONE} e a transferência fecha como DONE. */
	@Test
	void completar_com_destino_confirmado_remove_a_antiga() {
		when(catalog.findRebalance("reb_1"))
			.thenReturn(Optional.of(new StorageRebalance("reb_1", "sob_1", 0, "nd_cheio", 2, "nd_novo",
				StorageRebalance.State.PENDING, null, 10L, 10, "admin", NOW, null)))
			.thenReturn(Optional.of(new StorageRebalance("reb_1", "sob_1", 0, "nd_cheio", 2, "nd_novo",
				StorageRebalance.State.DONE, null, 10L, 10, "admin", NOW, NOW)));
		when(catalog.copies("sob_1")).thenReturn(
			List.of(copia(0, "nd_cheio"), new StoragePlacement("sob_1", 2, "nd_novo", 10L, SHA,
				StoragePlacement.State.OK, NOW)));

		StorageRebalance done = service.complete("admin", "reb_1");

		assertThat(done.state()).isEqualTo(StorageRebalance.State.DONE);
		verify(catalog).savePlacement(org.mockito.ArgumentMatchers.argThat(
			p -> p.replica() == 0 && p.state() == StoragePlacement.State.GONE));
		verify(catalog).transitionRebalance("reb_1", StorageRebalance.State.PENDING, StorageRebalance.State.DONE,
			null, NOW);
	}

	/**
	 * Desfazer recolhe a designação nova e <b>deixa a antiga intacta</b>. O estado da antiga é
	 * afirmado por verificação negativa: nenhum {@code savePlacement} com a réplica de origem.
	 */
	@Test
	void cancelar_no_meio_recolhe_a_nova_e_deixa_a_antiga() {
		when(catalog.findRebalance("reb_1"))
			.thenReturn(Optional.of(new StorageRebalance("reb_1", "sob_1", 0, "nd_cheio", 2, "nd_novo",
				StorageRebalance.State.PENDING, null, 10L, 10, "admin", NOW, null)))
			.thenReturn(Optional.of(new StorageRebalance("reb_1", "sob_1", 0, "nd_cheio", 2, "nd_novo",
				StorageRebalance.State.CANCELLED, "cancelado", 10L, 10, "admin", NOW, NOW)));
		when(catalog.copies("sob_1")).thenReturn(List.of(copia(0, "nd_cheio"),
			new StoragePlacement("sob_1", 2, "nd_novo", 10L, SHA, StoragePlacement.State.PENDING_REMOTE, NOW)));

		StorageRebalance cancelada = service.cancel("admin", "reb_1", "cancelado");

		assertThat(cancelada.state()).isEqualTo(StorageRebalance.State.CANCELLED);
		verify(catalog).savePlacement(org.mockito.ArgumentMatchers.argThat(
			p -> p.replica() == 2 && p.state() == StoragePlacement.State.GONE));
		verify(catalog, never()).savePlacement(org.mockito.ArgumentMatchers.argThat(p -> p.replica() == 0));
	}

	/**
	 * Cancelar depois de concluída é recusado: desfazer o DONE removeria a cópia nova depois de a
	 * antiga ter saído — o único caminho que deixaria o objeto sem nenhuma das duas.
	 */
	@Test
	void cancelar_depois_de_concluida_e_recusado() {
		when(catalog.findRebalance("reb_1")).thenReturn(Optional.of(new StorageRebalance("reb_1", "sob_1", 0,
			"nd_cheio", 2, "nd_novo", StorageRebalance.State.DONE, null, 10L, 10, "admin", NOW, NOW)));

		assertThatThrownBy(() -> service.cancel("admin", "reb_1", "tarde"))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "STORAGE_REBALANCE_FINISHED");
		verify(catalog, never()).savePlacement(any());
	}

	/** Transferência que perdeu a designação não continua viva: o operador é avisado do motivo. */
	@Test
	void transferencia_sem_designacao_pendente_e_considerada_orfã() {
		when(catalog.activeRebalances(StorageRebalanceService.MAX_PER_CALL))
			.thenReturn(List.of(new StorageRebalance("reb_1", "sob_1", 0, "nd_cheio", 2, "nd_novo",
				StorageRebalance.State.PENDING, null, 10L, 10, "admin", NOW, null)));
		when(catalog.copies("sob_1")).thenReturn(List.of(copia(0, "nd_cheio")));

		assertThat(service.stale()).extracting(StorageRebalance::id).containsExactly("reb_1");
	}

	private static StorageObject objeto() {
		return StorageObject.current("sob_1", "fotos", "a.jpg", "image/jpeg", 10L, SHA, StorageObjectState.STORED,
			"user", NOW.minusSeconds(3600), NOW.minusSeconds(3600), null);
	}

	private static StoragePlacement copia(int replica, String nodeId) {
		return new StoragePlacement("sob_1", replica, nodeId, 10L, SHA, StoragePlacement.State.OK, NOW);
	}

	private static NetworkNode node(String id, NodeStatus status) {
		return new NetworkNode(id, id, "owner", status, Set.of(NodeProfile.NODE_STORAGE), "sudeste", "BR", "1.0",
			NOW, 2, 4096, 51200, 50, null, null, null, null, null, null, null, 0, null, null);
	}
}