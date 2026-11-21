package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.StorageBucketPolicy;
import com.socialcoisa.rede.network.domain.ObjectMetadata;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Ciclo de vida do Storage (brief §14): expiração, retenção e a política por bucket.
 *
 * <p>A regra que estes testes protegem é a segunda metade do brief: <b>um nó desconectado não pode
 * restaurar silenciosamente arquivos já excluídos</b>. O ciclo de vida marca lápide e nunca apaga a
 * linha, então a coleta é o único caminho que remove — e ela só passa por lápide vencida.</p>
 */
class StorageLifecycleTests {

	private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

	private StorageCatalogRepository catalog;
	private StorageCatalogService cota;
	private StorageLifecycle lifecycle;

	@BeforeEach
	void setUp() {
		catalog = mock(StorageCatalogRepository.class);
		cota = mock(StorageCatalogService.class);
		lifecycle = new StorageLifecycle(catalog, mock(AuditTrail.class),
			Clock.fixed(NOW, ZoneOffset.UTC), cota);
	}

	@Test
	void objeto_vencido_recebe_lapide_e_nao_e_apagado() {
		StorageObject vencido = objeto("sob_1", "temp", "a.bin", StorageObjectState.STORED, null,
			NOW.minusSeconds(60));
		when(catalog.expired(NOW, StorageLifecycle.MAX_OBJECTS)).thenReturn(List.of(vencido));
		when(catalog.markDeleted("sob_1", NOW)).thenReturn(true);
		when(catalog.supersededBefore(any(), any(Integer.class))).thenReturn(List.of());

		StorageLifecycle.Report report = lifecycle.run(false);

		assertThat(report.expired()).isEqualTo(1);
		assertThat(report.expiredObjects()).extracting(StorageLifecycle.Expired::objectId).containsExactly("sob_1");
		// Lápide, nunca exclusão: a linha precisa existir para a coleta das cópias nos nós.
		verify(catalog).markDeleted("sob_1", NOW);
		verify(catalog, never()).markSuperseded(any(), any(), any(), any());
	}

	/**
	 * Objeto que outra gravação substituiu entre a leitura e a marcação não vira lápide: a transição
	 * condicional recusa, e a versão nova continua viva.
	 */
	@Test
	void objeto_que_deixou_de_ser_corrente_na_corrida_nao_e_lapidado() {
		StorageObject vencido = objeto("sob_1", "temp", "a.bin", StorageObjectState.STORED, "sob_2",
			NOW.minusSeconds(60));
		when(catalog.expired(NOW, StorageLifecycle.MAX_OBJECTS)).thenReturn(List.of(vencido));
		when(catalog.markDeleted("sob_1", NOW)).thenReturn(false);
		when(catalog.supersededBefore(any(), any(Integer.class))).thenReturn(List.of());

		StorageLifecycle.Report report = lifecycle.run(false);

		assertThat(report.expired()).isZero();
	}

	@Test
	void versao_substituida_so_sai_depois_da_retencao_do_bucket() {
		StorageObject antiga = objeto("sob_1", "fotos", "a.jpg", StorageObjectState.STORED, "sob_2",
			null);
		// Substituída há 10 dias; o bucket guarda 30.
		Instant substituida = NOW.minusSeconds(10L * 24 * 3600);
		antiga = comAtualizada(antiga, substituida);
		when(catalog.expired(NOW, StorageLifecycle.MAX_OBJECTS)).thenReturn(List.of());
		when(catalog.supersededBefore(NOW, StorageLifecycle.MAX_OBJECTS)).thenReturn(List.of(antiga));
		when(catalog.bucketPolicy("fotos"))
			.thenReturn(Optional.of(new StorageBucketPolicy("fotos", true, 30, null, "admin", substituida)));

		StorageLifecycle.Report report = lifecycle.run(false);

		assertThat(report.retained()).isZero();
		verify(catalog, never()).markDeleted(eq("sob_1"), any());
	}

	@Test
	void versao_substituida_vence_a_retencao_e_recebe_lapide() {
		StorageObject antiga = comAtualizada(objeto("sob_1", "fotos", "a.jpg", StorageObjectState.STORED, "sob_2",
			null), NOW.minusSeconds(40L * 24 * 3600));
		when(catalog.expired(NOW, StorageLifecycle.MAX_OBJECTS)).thenReturn(List.of());
		when(catalog.supersededBefore(NOW, StorageLifecycle.MAX_OBJECTS)).thenReturn(List.of(antiga));
		when(catalog.bucketPolicy("fotos"))
			.thenReturn(Optional.of(new StorageBucketPolicy("fotos", true, 30, null, "admin", NOW)));
		when(catalog.markDeleted("sob_1", NOW)).thenReturn(true);

		StorageLifecycle.Report report = lifecycle.run(false);

		assertThat(report.retained()).isEqualTo(1);
		assertThat(report.purgedVersions()).extracting(StorageLifecycle.Purged::objectId).containsExactly("sob_1");
	}

	/** Sem política, o padrão é guardar: a varredura não pode liberar o que ninguém configurou. */
	@Test
	void bucket_sem_politica_nunca_libera_versao() {
		StorageObject antiga = comAtualizada(objeto("sob_1", "solto", "a.jpg", StorageObjectState.STORED, "sob_2",
			null), NOW.minusSeconds(400L * 24 * 3600));
		when(catalog.expired(NOW, StorageLifecycle.MAX_OBJECTS)).thenReturn(List.of());
		when(catalog.supersededBefore(NOW, StorageLifecycle.MAX_OBJECTS)).thenReturn(List.of(antiga));
		when(catalog.bucketPolicy("solto")).thenReturn(Optional.empty());

		StorageLifecycle.Report report = lifecycle.run(false);

		assertThat(report.retained()).isZero();
		verify(catalog, never()).markDeleted(eq("sob_1"), any());
	}

	/**
	 * A expiração tira o objeto da conta do banco; o cache da cota tem de esquecer o dono.
	 *
	 * <p>Antes, quem deixava um objeto vencer continuava barrado pela cota até o processo
	 * reiniciar — o banco já não contava o objeto e o cache ainda contava.</p>
	 */
	@Test
	void expirar_esquece_a_conta_do_dono() {
		StorageObject vencido = objeto("sob_v", "fotos", "a.jpg", StorageObjectState.STORED, null,
			NOW.minusSeconds(60));
		when(catalog.expired(NOW, StorageLifecycle.MAX_OBJECTS)).thenReturn(List.of(vencido));
		when(catalog.supersededBefore(NOW, StorageLifecycle.MAX_OBJECTS)).thenReturn(List.of());
		when(catalog.markDeleted("sob_v", NOW)).thenReturn(true);

		lifecycle.run(false);

		verify(cota).esquecerUsoDoParticipante("user");
	}

	/** {@code dryRun} relata sem gravar: é a primeira coisa que um operador faz num incidente. */
	@Test
	void dry_run_revela_sem_marcar() {
		when(catalog.expired(NOW, StorageLifecycle.MAX_OBJECTS)).thenReturn(List.of(
			objeto("sob_1", "temp", "a.bin", StorageObjectState.STORED, null, NOW.minusSeconds(60))));
		when(catalog.supersededBefore(any(), any(Integer.class))).thenReturn(List.of());

		StorageLifecycle.Report report = lifecycle.run(true);

		assertThat(report.dryRun()).isTrue();
		assertThat(report.expired()).isEqualTo(1);
		verify(catalog, never()).markDeleted(any(), any());
	}

	/**
	 * Retenção num bucket sem versionamento é configuração que nunca agiria. Recusar é melhor que
	 * aceitar calado — o operador acharia que configurou algo.
	 */
	@Test
	void retencao_sem_versionamento_e_recusada() {
		StorageCatalogService service = StorageCatalogTestRig.service(catalog);

		assertThatThrownBy(() -> service.putBucketPolicy("admin", "fotos", false, 30, null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("versionamento");
		verify(catalog, never()).saveBucketPolicy(any());
	}

	@Test
	void expiracao_zero_dia_e_recusada() {
		StorageCatalogService service = StorageCatalogTestRig.service(catalog);

		assertThatThrownBy(() -> service.putBucketPolicy("admin", "temp", false, null, 0))
			.isInstanceOf(ApiException.class);
	}

	private static StorageObject objeto(String id, String bucket, String key, StorageObjectState state,
			String supersededBy, Instant expiresAt) {
		return new StorageObject(id, bucket, key, "application/octet-stream", 10L, "a".repeat(64), state, "user",
			NOW.minusSeconds(86400), NOW.minusSeconds(86400), null, 1L, supersededBy, expiresAt,
			ObjectMetadata.EMPTY, null, null);
	}

	private static StorageObject comAtualizada(StorageObject o, Instant updatedAt) {
		return new StorageObject(o.id(), o.bucket(), o.key(), o.contentType(), o.bytes(), o.sha256(), o.state(),
			o.createdBy(), o.createdAt(), updatedAt, o.deletedAt(), o.version(), o.supersededBy(), o.expiresAt(),
			o.metadata(), o.cacheControl(), o.contentDisposition());
	}

	/** O serviço com os colaboradores mínimos — a política não precisa do resto para ser validada. */
	static final class StorageCatalogTestRig {

		static StorageCatalogService service(StorageCatalogRepository catalog) {
			return new StorageCatalogService(catalog, mock(AuditTrail.class), Clock.fixed(NOW, ZoneOffset.UTC),
				1_000_000L, "/tmp/enois-test-storage", mock(StoragePlacementService.class), null);
		}

		private StorageCatalogTestRig() {
		}
	}
}