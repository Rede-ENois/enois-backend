package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * A coleta de lápides do Storage: o que ela decide e o que ela se recusa a fazer.
 *
 * <p>O teste de rota cobre o caminho feliz; aqui ficam as decisões que só se provam forçando o
 * estado — em especial a <b>corrida</b> entre a página lida e a linha relida, que é a razão de a
 * coleta revisitar o objeto antes de recolher as cópias.</p>
 */
class StorageTombstoneCollectorTests {

	private static final Instant AGORA = Instant.parse("2026-11-16T12:00:00Z");

	private StorageCatalogRepository catalog;
	private AuditTrail audit;
	private StorageTombstoneCollector coletor;

	@BeforeEach
	void setUp() {
		catalog = mock(StorageCatalogRepository.class);
		audit = mock(AuditTrail.class);
		coletor = new StorageTombstoneCollector(catalog, audit, Clock.fixed(AGORA, ZoneOffset.UTC), 72);
	}

	private static StorageObject objeto(String id, StorageObjectState estado, Instant deletedAt) {
		return StorageObject.current(id, "bucket", id + ".bin", "application/octet-stream", 10L, "a".repeat(64), estado,
			"user-test", deletedAt.minusSeconds(3600), deletedAt, deletedAt);
	}

	/**
	 * Entre a página e a decisão, um upload marca o objeto de volta como {@code STORED}. A lápide
	 * que a página trouxe já não vale: recolher as cópias de um objeto vivo apagaria o corpo que o
	 * cliente acabou de gravar.
	 */
	@Test
	void objeto_que_voltou_a_vivo_nao_tem_as_copias_recolhidas() {
		var lapide = objeto("sob_vivo", StorageObjectState.DELETED, AGORA.minus(Duration.ofDays(5)));
		when(catalog.listDeletedBefore(any(), any(), any(), anyInt())).thenReturn(List.of(lapide))
			.thenReturn(List.of());
		when(catalog.findById("sob_vivo"))
			.thenReturn(Optional.of(objeto("sob_vivo", StorageObjectState.STORED, AGORA.minus(Duration.ofDays(5)))));

		var relatorio = coletor.collect(false);

		assertThat(relatorio.collected()).isEmpty();
		// Nem a leitura de cópias: objeto vivo não é assunto da coleta.
		verify(catalog, never()).copies("sob_vivo");
		verify(catalog, never()).savePlacement(any());
	}

	/** Objeto que sumiu do catálogo entre a página e a releitura também não é tocado. */
	@Test
	void objeto_que_sumiu_entre_a_pagina_e_a_decisao_nao_e_tocado() {
		var lapide = objeto("sob_sumiu", StorageObjectState.DELETED, AGORA.minus(Duration.ofDays(5)));
		when(catalog.listDeletedBefore(any(), any(), any(), anyInt())).thenReturn(List.of(lapide))
			.thenReturn(List.of());
		when(catalog.findById("sob_sumiu")).thenReturn(Optional.empty());

		assertThat(coletor.collect(false).collected()).isEmpty();
		verify(catalog, never()).savePlacement(any());
	}

	/**
	 * O cursor avança mesmo quando a página inteira foi pulada: sem isso, um lote de lápides vivas
	 * consome todas as páginas relendo a mesma linha (medido — 20 páginas para uma lápide).
	 */
	@Test
	void o_cursor_avanca_mesmo_quando_a_pagina_nao_rende() {
		var viva = objeto("sob_pulada", StorageObjectState.DELETED, AGORA.minus(Duration.ofDays(5)));
		when(catalog.listDeletedBefore(any(), any(), any(), anyInt()))
			.thenReturn(List.of(viva))
			// A segunda página é vazia: com o cursor preso, a primeira seria devolvida de novo.
			.thenReturn(List.of());
		when(catalog.findById("sob_pulada"))
			.thenReturn(Optional.of(objeto("sob_pulada", StorageObjectState.STORED, AGORA.minus(Duration.ofDays(5)))));

		coletor.collect(false);

		// Primeira chamada com cursor nulo; a segunda já avança pelo par (bucket, chave) da página.
		verify(catalog).listDeletedBefore(any(), eq(null), eq(null), anyInt());
		verify(catalog).listDeletedBefore(any(), eq("bucket"), eq("sob_pulada.bin"), anyInt());
	}

	/** Lápide dentro da janela não entra na varredura: a consulta é a mesma regra da retenção. */
	@Test
	void a_varredura_pede_a_janela_de_retencao_ao_banco() {
		when(catalog.listDeletedBefore(any(), any(), any(), anyInt())).thenReturn(List.of());

		coletor.collect(false);

		verify(catalog).listDeletedBefore(eq(AGORA.minus(Duration.ofHours(72))), any(), any(), anyInt());
	}

	@Test
	void dry_run_nao_grava_nem_audita() {
		var lapide = objeto("sob_seca", StorageObjectState.DELETED, AGORA.minus(Duration.ofDays(5)));
		when(catalog.listDeletedBefore(any(), any(), any(), anyInt())).thenReturn(List.of(lapide))
			.thenReturn(List.of());
		when(catalog.findById("sob_seca")).thenReturn(Optional.of(lapide));
		when(catalog.copies("sob_seca")).thenReturn(List.of(
			new com.socialcoisa.rede.network.domain.StoragePlacement("sob_seca", 0, "nd_cp", 10, "a".repeat(64),
				com.socialcoisa.rede.network.domain.StoragePlacement.State.OK, AGORA)));

		var relatorio = coletor.collect(true);

		assertThat(relatorio.dryRun()).isTrue();
		assertThat(relatorio.collected()).hasSize(1);
		verify(catalog, never()).savePlacement(any());
		verify(audit, never()).record(anyString(), anyString(), anyString(), anyString());
	}

	/** A coleta real grava GONE e audita a operação — a cópia é o que sai da durabilidade. */
	@Test
	void a_coleta_real_grava_gone_e_audita() {
		var lapide = objeto("sob_coleta", StorageObjectState.DELETED, AGORA.minus(Duration.ofDays(5)));
		when(catalog.listDeletedBefore(any(), any(), any(), anyInt())).thenReturn(List.of(lapide))
			.thenReturn(List.of());
		when(catalog.findById("sob_coleta")).thenReturn(Optional.of(lapide));
		when(catalog.copies("sob_coleta")).thenReturn(List.of(
			new com.socialcoisa.rede.network.domain.StoragePlacement("sob_coleta", 0, "nd_cp", 10, "a".repeat(64),
				com.socialcoisa.rede.network.domain.StoragePlacement.State.OK, AGORA)));

		var relatorio = coletor.collect(false);

		assertThat(relatorio.collected()).hasSize(1);
		assertThat(relatorio.collected().get(0).copiesCollected()).isEqualTo(1);
		verify(catalog).savePlacement(org.mockito.ArgumentMatchers.argThat(p ->
			p.replica() == 0 && p.state() == com.socialcoisa.rede.network.domain.StoragePlacement.State.GONE));
		verify(audit).record(eq("system"), eq("STORAGE_TOMBSTONES_COLLECTED"), eq("storage"), anyString());
	}
}