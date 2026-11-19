package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * O nó devolve o que mediu no próprio disco e o Control Plane só rebaixa a <b>cópia dele</b>.
 *
 * <p>É o furo por onde passaria o ataque mais barato da rede: se um nó qualquer conseguisse marcar a
 * cópia de outro como {@code STALE} só por listá-la no relatório, qualquer participante derrubaria a
 * durabilidade do cluster com um POST. Estes testes fixam o filtro por dono.</p>
 */
class StorageNodeScrubTests {

	private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
	private static final String SHA = "a".repeat(64);

	private StorageCatalogRepository catalog;
	private AuditTrail audit;
	private StorageCatalogService service;

	@BeforeEach
	void setUp() {
		catalog = mock(StorageCatalogRepository.class);
		audit = mock(AuditTrail.class);
		service = new StorageCatalogService(catalog, audit, Clock.fixed(NOW, ZoneOffset.UTC), 10_000, "target/t-obj",
			mock(StoragePlacementService.class), null);
		when(catalog.findById("sob_1")).thenReturn(Optional.of(StorageObject.current("sob_1", "fotos", "gato.jpg",
			"image/jpeg", 8L, SHA, StorageObjectState.STORED, "u", NOW, NOW, null)));
	}

	private static NetworkNode no(String id) {
		return new NetworkNode(id, id, "u", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE), "sudeste", "BR",
			"1.0", NOW, 2, 4096, 102400, 100, null, null, null, null, null, 0, null, null);
	}

	private void colocacao(String nodeId, StoragePlacement.State state) {
		when(catalog.placementOn("sob_1", nodeId))
			.thenReturn(Optional.of(new StoragePlacement("sob_1", 1, nodeId, 8L, SHA, state, NOW)));
	}

	@Test
	void copia_propria_que_nao_confere_fica_stale() {
		colocacao("nd_a", StoragePlacement.State.OK);

		int marcadas = service.reportDirtyReplicas(no("nd_a"),
			List.of(new StorageCatalogService.Divergente("sob_1", "b".repeat(64))));

		assertThat(marcadas).isEqualTo(1);
		verify(catalog).savePlacement(org.mockito.ArgumentMatchers.argThat(p -> p.state() == StoragePlacement.State.STALE
			&& p.nodeId().equals("nd_a")));
		verify(audit).record(eq("nd_a"), eq("STORAGE_REPLICA_MISMATCH"), eq("sob_1"), anyString());
	}

	@Test
	void no_nao_rebaixa_a_copia_de_outro_no() {
		colocacao("nd_a", StoragePlacement.State.OK);
		// nd_b declara que a cópia de nd_a está ruim. Não é dele: nada muda.
		when(catalog.placementOn("sob_1", "nd_b")).thenReturn(Optional.empty());

		int marcadas = service.reportDirtyReplicas(no("nd_b"),
			List.of(new StorageCatalogService.Divergente("sob_1", "b".repeat(64))));

		assertThat(marcadas).isZero();
		verify(catalog, never()).savePlacement(org.mockito.ArgumentMatchers.any());
		verify(audit, never()).record(anyString(), anyString(), anyString(), anyString());
	}

	@Test
	void copia_ja_stale_nao_e_regravada_nem_reauditada() {
		colocacao("nd_a", StoragePlacement.State.STALE);

		int marcadas = service.reportDirtyReplicas(no("nd_a"),
			List.of(new StorageCatalogService.Divergente("sob_1", "b".repeat(64))));

		// Idempotência: o nó que repete o relatório não infla a auditoria.
		assertThat(marcadas).isZero();
		verify(audit, never()).record(anyString(), anyString(), anyString(), anyString());
	}

	@Test
	void digest_esperado_vem_do_catalogo_e_nao_do_relatorio_do_no() {
		colocacao("nd_a", StoragePlacement.State.OK);

		service.reportDirtyReplicas(no("nd_a"),
			List.of(new StorageCatalogService.Divergente("sob_1", "b".repeat(64))));

		// O nó declarou só o que MEDIU. O esperado é o da colocação; o tamanho também. Um nó que
		// escolhesse o esperado faria o próprio digest passar por íntegro.
		verify(catalog).savePlacement(org.mockito.ArgumentMatchers.argThat(p -> p.bytes() == 8L
			&& p.sha256().equals("b".repeat(64)) && p.replica() == 1));
	}
}