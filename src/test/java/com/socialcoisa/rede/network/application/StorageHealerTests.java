package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * Healing do Storage: a lacuna da varredura vira designação para um nó, e nada além disso.
 *
 * <p>O que estes testes seguram, e por quê:</p>
 * <ul>
 * <li><b>a cópia {@code STALE} não conta como origem</b> — curar a partir dela espalharia o
 * conteúdo errado; a lacuna fica com motivo próprio em vez de "não há nó", que é outra coisa;</li>
 * <li><b>{@code dryRun} não grava</b> — quem quer ver o efeito não pode pagar o tráfego;</li>
 * <li><b>nada é marcado {@code OK}</b> — a confirmação é do nó, com o que ele mediu. Um healer que
 * declarasse cópia pronta mataria a única prova de que a cópia existe;</li>
 * <li><b>o número da réplica continua depois do maior existente</b> — reaproveitar um número
 * colidiria com a chave {@code (object_id, replica)} e sobrescreveria a cópia antiga;</li>
 * <li><b>{@code ALREADY_DESIGNATED}</b> — designação pendente tem dono; designar de novo é só
 * reiniciar o relógio do retry.</li>
 * </ul>
 */
class StorageHealerTests {

	private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

	private StorageScrubber scrubber;
	private StoragePlacementService placement;
	private StorageCatalogRepository catalog;
	private AuditTrail audit;
	private StorageHealer healer;
	private final List<StoragePlacement> gravadas = new ArrayList<>();

	@BeforeEach
	void setUp() {
		scrubber = mock(StorageScrubber.class);
		placement = mock(StoragePlacementService.class);
		catalog = mock(StorageCatalogRepository.class);
		audit = mock(AuditTrail.class);
		gravadas.clear();
		// A transação real não importa aqui: o que se verifica é o efeito no catálogo.
		PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
		when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		healer = new StorageHealer(scrubber, placement, catalog, audit, tx, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static NetworkNode storageNode(String id, String region) {
		return new NetworkNode(id, "no-" + id, "user-" + id, NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE),
			region, "BR", "1.0.0", NOW, 4, 4096, 102400, 100, null, null, null, null, 5000L, 0, null, null);
	}

	private static StorageObject stored(String id, long bytes) {
		return new StorageObject(id, "fotos", id + ".jpg", "image/jpeg", bytes, "a".repeat(64),
			StorageObjectState.STORED, "u", NOW, NOW, null);
	}

	private static StorageScrubber.Report gap(String... objectIds) {
		List<StorageScrubber.Report.UnderReplicated> lacunas = new ArrayList<>();
		for (String id : objectIds) {
			lacunas.add(new StorageScrubber.Report.UnderReplicated(id, 1, 1, List.of("nd_cp")));
		}
		return new StorageScrubber.Report(2, objectIds.length, lacunas, 0, List.of());
	}

	private void expectSaved() {
		doAnswer(inv -> {
			gravadas.add(inv.getArgument(0));
			return null;
		}).when(catalog).savePlacement(any());
	}

	@Test
	void lacuna_vira_designacao_pendente_para_o_no_escolhido() {
		when(scrubber.scan(isNull(), anyInt(), anyBoolean())).thenReturn(gap("sob_1"));
		when(catalog.findById("sob_1")).thenReturn(Optional.of(stored("sob_1", 2048)));
		when(catalog.placements("sob_1")).thenReturn(List.of());
		NetworkNode no = storageNode("nd_a", "sudeste");
		when(placement.chooseReplicas(eq("sob_1"), anyLong(), any(Instant.class))).thenReturn(List.of(no));
		expectSaved();

		StorageHealer.Report relatorio = healer.heal("adm_1", null, 100, false);

		assertThat(relatorio.designated()).hasSize(1);
		assertThat(relatorio.designated().get(0).nodeId()).isEqualTo("nd_a");
		assertThat(gravadas).hasSize(1);
		StoragePlacement p = gravadas.get(0);
		assertThat(p.objectId()).isEqualTo("sob_1");
		// PENDENTE: quem diz "a cópia existe" é o nó, com o que ele mediu ao gravar.
		assertThat(p.state()).isEqualTo(StoragePlacement.State.PENDING_REMOTE);
		assertThat(p.sha256()).isEqualTo("a".repeat(64));
		verify(audit).record(eq("adm_1"), eq("STORAGE_REPLICA_HEALED"), eq("sob_1"), anyString());
	}

	@Test
	void dry_run_mostra_o_que_seria_feito_e_nao_grava_nada() {
		when(scrubber.scan(isNull(), anyInt(), anyBoolean())).thenReturn(gap("sob_1"));
		when(catalog.findById("sob_1")).thenReturn(Optional.of(stored("sob_1", 2048)));
		when(catalog.placements("sob_1")).thenReturn(List.of());
		when(placement.chooseReplicas(eq("sob_1"), anyLong(), any(Instant.class)))
			.thenReturn(List.of(storageNode("nd_a", "sudeste")));

		StorageHealer.Report relatorio = healer.heal("adm_1", null, 100, true);

		assertThat(relatorio.dryRun()).isTrue();
		assertThat(relatorio.designated()).hasSize(1);
		assertThat(relatorio.designated().get(0).nodeId()).isEqualTo("nd_a");
		verify(catalog, never()).savePlacement(any());
		verify(audit, never()).record(anyString(), anyString(), anyString(), anyString());
	}

	@Test
	void replica_nova_continua_depois_da_maior_existente() {
		when(scrubber.scan(isNull(), anyInt(), anyBoolean())).thenReturn(gap("sob_1"));
		when(catalog.findById("sob_1")).thenReturn(Optional.of(stored("sob_1", 2048)));
		// A réplica 1 já tem dono num nó que a perdeu (STALE): a nova não pode ser a 1.
		when(catalog.placements("sob_1")).thenReturn(List.of(
			new StoragePlacement("sob_1", 0, "nd_cp", 2048, "a".repeat(64), StoragePlacement.State.OK, NOW),
			new StoragePlacement("sob_1", 2, "nd_velho", 2048, "a".repeat(64), StoragePlacement.State.STALE, NOW)));
		when(placement.chooseReplicas(eq("sob_1"), anyLong(), any(Instant.class)))
			.thenReturn(List.of(storageNode("nd_a", "sudeste")));
		expectSaved();

		healer.heal("adm_1", null, 100, false);

		assertThat(gravadas).hasSize(1);
		assertThat(gravadas.get(0).replica()).isEqualTo(3);
	}

	@Test
	void so_copia_stale_nao_e_origem_para_replicar() {
		when(scrubber.scan(isNull(), anyInt(), anyBoolean())).thenReturn(gap("sob_1"));
		when(catalog.findById("sob_1")).thenReturn(Optional.of(stored("sob_1", 2048)));
		when(catalog.placements("sob_1")).thenReturn(List.of(
			new StoragePlacement("sob_1", 1, "nd_x", 2048, "b".repeat(64), StoragePlacement.State.STALE, NOW)));
		// Nenhum nó: sem isso o motivo diria NO_CANDIDATE, escondendo que há cópia suspeita.
		when(placement.chooseReplicas(eq("sob_1"), anyLong(), any(Instant.class))).thenReturn(List.of());

		StorageHealer.Report relatorio = healer.heal("adm_1", null, 100, false);

		assertThat(relatorio.designated()).isEmpty();
		assertThat(relatorio.skipped()).hasSize(1);
		assertThat(relatorio.skipped().get(0).reason()).isEqualTo("STALE_COPIES_ONLY");
		verify(catalog, never()).savePlacement(any());
	}

	@Test
	void sem_no_livre_a_lacuna_fica_com_motivo_proprio() {
		when(scrubber.scan(isNull(), anyInt(), anyBoolean())).thenReturn(gap("sob_1"));
		when(catalog.findById("sob_1")).thenReturn(Optional.of(stored("sob_1", 2048)));
		when(catalog.placements("sob_1")).thenReturn(List.of(
			new StoragePlacement("sob_1", 0, "nd_cp", 2048, "a".repeat(64), StoragePlacement.State.OK, NOW)));
		when(placement.chooseReplicas(eq("sob_1"), anyLong(), any(Instant.class))).thenReturn(List.of());

		StorageHealer.Report relatorio = healer.heal("adm_1", null, 100, false);

		assertThat(relatorio.skipped().get(0).reason()).isEqualTo("NO_CANDIDATE");
		assertThat(relatorio.replicasExpected()).isEqualTo(2);
	}

	@Test
	void designacao_pendente_ja_tem_dono_e_nao_e_reescolhida() {
		when(scrubber.scan(isNull(), anyInt(), anyBoolean())).thenReturn(gap("sob_1"));
		when(catalog.findById("sob_1")).thenReturn(Optional.of(stored("sob_1", 2048)));
		when(catalog.placements("sob_1")).thenReturn(List.of(
			new StoragePlacement("sob_1", 1, "nd_a", 2048, "a".repeat(64),
				StoragePlacement.State.PENDING_REMOTE, NOW)));

		StorageHealer.Report relatorio = healer.heal("adm_1", null, 100, false);

		assertThat(relatorio.skipped().get(0).reason()).isEqualTo("ALREADY_DESIGNATED");
		verify(placement, never()).chooseReplicas(anyString(), anyLong(), any(Instant.class));
		verify(catalog, never()).savePlacement(any());
	}

	@Test
	void objeto_lapidado_entre_a_varredura_e_a_cura_nao_vira_designacao() {
		when(scrubber.scan(isNull(), anyInt(), anyBoolean())).thenReturn(gap("sob_1"));
		when(catalog.findById("sob_1")).thenReturn(Optional.of(new StorageObject("sob_1", "fotos", "a.jpg",
			"image/jpeg", 10L, "a".repeat(64), StorageObjectState.DELETED, "u", NOW, NOW, NOW)));

		StorageHealer.Report relatorio = healer.heal("adm_1", null, 100, false);

		assertThat(relatorio.skipped().get(0).reason()).isEqualTo("BODY_MISSING");
		verify(catalog, never()).savePlacement(any());
	}

	@Test
	void designa_no_maximo_o_que_falta_mesmo_com_mais_candidatos() {
		when(scrubber.scan(isNull(), anyInt(), anyBoolean())).thenReturn(gap("sob_1"));
		when(catalog.findById("sob_1")).thenReturn(Optional.of(stored("sob_1", 2048)));
		when(catalog.placements("sob_1")).thenReturn(List.of());
		// Três nós livres, mas só falta UMA cópia: designar as três criaria duplicidade que o
		// alvo não pediu e o relatório de equilíbrio acusaria como desbalanceamento.
		when(placement.chooseReplicas(eq("sob_1"), anyLong(), any(Instant.class))).thenReturn(List.of(
			storageNode("nd_a", "sudeste"), storageNode("nd_b", "nordeste"), storageNode("nd_c", "sul")));
		expectSaved();

		StorageHealer.Report relatorio = healer.heal("adm_1", null, 100, false);

		assertThat(relatorio.designated()).hasSize(1);
		assertThat(gravadas).hasSize(1);
	}
}