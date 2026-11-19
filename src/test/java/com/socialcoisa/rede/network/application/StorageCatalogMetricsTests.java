package com.socialcoisa.rede.network.application;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.StorageOperationMetrics.Resultado;
import com.socialcoisa.rede.network.domain.ObjectMetadata;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O contador de operação <b>ligado no caminho real</b> do catálogo (§17, V366).
 *
 * <p>O teste da série prova a matemática; este prova a fiação — o defeito mais provável aqui não é a
 * conta errada, é a métrica que existe e ninguém chama (campo gravado e nunca lido, o mesmo erro do
 * ciclo 54).</p>
 *
 * <p>As três asserções que discriminam: {@code GET} que não acha o objeto conta {@code CLIENT} (não
 * OK e não SERVER); falha de leitura com 5xx conta {@code SERVER}; e o serviço construído <b>sem</b>
 * contador continua funcionando — a métrica é observação, não pré-requisito para servir um arquivo.</p>
 */
class StorageCatalogMetricsTests {

	private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

	private static StorageObject objeto(StorageObjectState estado) {
		return new StorageObject("obj_1", "bkt", "foto.png", "image/png", 10L, "abc", estado, "user_1", NOW, NOW,
			null, 1, null, null, ObjectMetadata.EMPTY, null, null);
	}

	@Test
	void get_que_nao_acha_o_objeto_conta_como_erro_de_cliente() {
		var repo = mock(com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository.class);
		var audit = mock(com.socialcoisa.rede.shared.audit.AuditTrail.class);
		var metrics = mock(StorageOperationMetrics.class);
		var servico = new StorageCatalogService(repo, audit, Clock.fixed(NOW, ZoneOffset.UTC), 1024L,
			"/tmp/enois-metrics", "", mock(StoragePlacementService.class), null, metrics);
		when(repo.find("bkt", "sumiu")).thenReturn(Optional.empty());

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> servico.requireDownloadable("bkt", "sumiu"))
			.isInstanceOf(ApiException.class);

		verify(metrics).record(eq("GET"), eq(Resultado.CLIENT), anyLong());
	}

	@Test
	void get_de_objeto_servivel_conta_ok() {
		var repo = mock(com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository.class);
		var audit = mock(com.socialcoisa.rede.shared.audit.AuditTrail.class);
		var metrics = mock(StorageOperationMetrics.class);
		var servico = new StorageCatalogService(repo, audit, Clock.fixed(NOW, ZoneOffset.UTC), 1024L,
			"/tmp/enois-metrics", "", mock(StoragePlacementService.class), null, metrics);
		when(repo.find("bkt", "foto.png")).thenReturn(Optional.of(objeto(StorageObjectState.STORED)));

		servico.requireDownloadable("bkt", "foto.png");

		verify(metrics).record(eq("GET"), eq(Resultado.OK), anyLong());
	}

	@Test
	void falha_de_leitura_com_cinco_centos_conta_como_servidor() {
		var repo = mock(com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository.class);
		var audit = mock(com.socialcoisa.rede.shared.audit.AuditTrail.class);
		var metrics = mock(StorageOperationMetrics.class);
		var servico = new StorageCatalogService(repo, audit, Clock.fixed(NOW, ZoneOffset.UTC), 1024L,
			"/tmp/enois-metrics", "", mock(StoragePlacementService.class), null, metrics);
		when(repo.find(anyString(), anyString()))
			.thenThrow(new ApiException(500, "STORAGE_ROOT_UNAVAILABLE", "indisponivel"));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> servico.requireDownloadable("bkt", "x"))
			.isInstanceOf(ApiException.class);

		verify(metrics).record(eq("GET"), eq(Resultado.SERVER), anyLong());
	}

	@Test
	void sem_contador_o_catalogo_continua_servindo() {
		var repo = mock(com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository.class);
		var audit = mock(com.socialcoisa.rede.shared.audit.AuditTrail.class);
		var servico = new StorageCatalogService(repo, audit, Clock.fixed(NOW, ZoneOffset.UTC), 1024L,
				"/tmp/enois-metrics", "", mock(StoragePlacementService.class), null, null);
		when(repo.find("bkt", "foto.png")).thenReturn(Optional.of(objeto(StorageObjectState.STORED)));

		assertThat(servico.requireDownloadable("bkt", "foto.png").id()).isEqualTo("obj_1");
	}

	@Test
	void head_conta_como_head_e_nao_como_leitura_de_corpo() {
		var repo = mock(com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository.class);
		var audit = mock(com.socialcoisa.rede.shared.audit.AuditTrail.class);
		var metrics = mock(StorageOperationMetrics.class);
		var servico = new StorageCatalogService(repo, audit, Clock.fixed(NOW, ZoneOffset.UTC), 1024L,
				"/tmp/enois-metrics", "", mock(StoragePlacementService.class), null, metrics);
		when(repo.find("bkt", "foto.png")).thenReturn(Optional.of(objeto(StorageObjectState.STORED)));

		servico.requireDownloadable("bkt", "foto.png", "HEAD");

		verify(metrics).record(eq("HEAD"), eq(Resultado.OK), anyLong());
		verify(metrics, never()).record(eq("GET"), eq(Resultado.OK), anyLong());
	}

	@Test
	void listagem_conta_como_list() {
		var repo = mock(com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository.class);
		var audit = mock(com.socialcoisa.rede.shared.audit.AuditTrail.class);
		var metrics = mock(StorageOperationMetrics.class);
		var servico = new StorageCatalogService(repo, audit, Clock.fixed(NOW, ZoneOffset.UTC), 1024L,
				"/tmp/enois-metrics", "", mock(StoragePlacementService.class), null, metrics);
		when(repo.list(eq("bkt"), any(), any(), anyInt())).thenReturn(java.util.List.of());

		servico.list("bkt", null, null, 10);

		verify(metrics).record(eq("LIST"), eq(Resultado.OK), anyLong());
	}

	@Test
	void erro_nao_vira_contagem_de_ok_por_engano() {
		var metrics = mock(StorageOperationMetrics.class);
		var repo = mock(com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository.class);
		var audit = mock(com.socialcoisa.rede.shared.audit.AuditTrail.class);
		var servico = new StorageCatalogService(repo, audit, Clock.fixed(NOW, ZoneOffset.UTC), 1024L,
			"/tmp/enois-metrics", "", mock(StoragePlacementService.class), null, metrics);
		when(repo.find(any(), any())).thenThrow(new IllegalStateException("quebrou"));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> servico.requireDownloadable("bkt", "x"))
			.isInstanceOf(IllegalStateException.class);

		// Exceção que não é ApiException não é "erro do cliente": conta como SERVER, nunca OK.
		verify(metrics).record(eq("GET"), eq(Resultado.SERVER), anyLong());
		verify(metrics, never()).record(eq("GET"), eq(Resultado.OK), anyLong());
	}
}