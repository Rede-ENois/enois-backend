package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.network.domain.TaskStatus;
import com.socialcoisa.rede.network.domain.TaskType;

import tools.jackson.databind.ObjectMapper;

/**
 * Conferência do {@code OBJECT_PUT}: a declaração do nó só vale se o catálogo tiver a cópia.
 *
 * <p>O caso que motivou isto: o {@code *) } do {@code node-runtime.sh} responde {@code DONE} com um
 * eco do {@code idempotencyKey} para todo tipo sem executor — inclusive {@code OBJECT_PUT}. Esse
 * eco tem de ser <b>recusado</b>, e o motivo tem de ser a ausência de cópia, não o formato do eco.
 * Um conferente que olhasse só o {@code output} aceitaria o eco ou recusaria por "payload inválido",
 * dizendo ao operador a coisa errada.</p>
 */
class StorageResultVerifierTests {

	private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
	private static final String SHA = "a".repeat(64);

	private StorageCatalogRepository catalog;
	private StorageResultVerifierAdapter verifier;

	@BeforeEach
	void setUp() {
		catalog = mock(StorageCatalogRepository.class);
		verifier = new StorageResultVerifierAdapter(catalog, new ObjectMapper());
	}

	private static NetworkTask task(String payload, String nodeId) {
		return new NetworkTask("tk_1", TaskType.OBJECT_PUT, TaskStatus.DISPATCHED, 1, "idem", payload,
			DataClass.INTERNAL, null, nodeId, NOW.plusSeconds(600), null, null, null, null, null, null, NOW, NOW,
			nodeId);
	}

	private static String payload(String objectId) {
		return "{\"objectId\":\"" + objectId + "\",\"sha256\":\"" + SHA + "\",\"bytes\":10,\"replica\":1}";
	}

	private static StorageObject stored() {
		return new StorageObject("sob_1", "fotos", "a.jpg", "image/jpeg", 10L, SHA, StorageObjectState.STORED,
			"u", NOW, NOW, null);
	}

	@Test
	void eco_do_runtime_sem_executor_e_recusado_por_falta_de_copia() {
		when(catalog.findById("sob_1")).thenReturn(Optional.of(stored()));
		// Exatamente o que o `*)` do node-runtime.sh posta hoje.
		when(catalog.placements("sob_1")).thenReturn(List.of(
			new StoragePlacement("sob_1", 0, "nd_cp", 10, SHA, StoragePlacement.State.OK, NOW)));

		var v = verifier.verify(task(payload("sob_1"), "nd_a"), "{\"echo\":\"storage:sob_1:1\"}");

		assertThat(v.ok()).isFalse();
		// O motivo é o certo: não há CÓPIA neste nó, não "o eco está no formato errado".
		assertThat(v.status()).isEqualTo("STORAGE_REPLICA_NOT_CONFIRMED");
		assertThat(v.retryable()).isTrue();
	}

	@Test
	void copia_confirmada_neste_no_aceita_o_done() {
		when(catalog.findById("sob_1")).thenReturn(Optional.of(stored()));
		when(catalog.placements("sob_1")).thenReturn(List.of(
			new StoragePlacement("sob_1", 0, "nd_cp", 10, SHA, StoragePlacement.State.OK, NOW),
			new StoragePlacement("sob_1", 1, "nd_a", 10, SHA, StoragePlacement.State.OK, NOW)));

		var v = verifier.verify(task(payload("sob_1"), "nd_a"), "{\"bytes\":10,\"sha256\":\"" + SHA + "\"}");

		assertThat(v.ok()).isTrue();
	}

	@Test
	void copia_em_OUTRO_no_nao_vale_para_este() {
		when(catalog.findById("sob_1")).thenReturn(Optional.of(stored()));
		// A cópia existe -- no nó B. O nó A declarar sucesso sem tê-la é a mesma mentira.
		when(catalog.placements("sob_1")).thenReturn(List.of(
			new StoragePlacement("sob_1", 0, "nd_cp", 10, SHA, StoragePlacement.State.OK, NOW),
			new StoragePlacement("sob_1", 1, "nd_b", 10, SHA, StoragePlacement.State.OK, NOW)));

		var v = verifier.verify(task(payload("sob_1"), "nd_a"), "");

		assertThat(v.ok()).isFalse();
		assertThat(v.status()).isEqualTo("STORAGE_REPLICA_NOT_CONFIRMED");
	}

	@Test
	void copia_pendente_nao_e_copia_confirmada() {
		when(catalog.findById("sob_1")).thenReturn(Optional.of(stored()));
		when(catalog.placements("sob_1")).thenReturn(List.of(
			new StoragePlacement("sob_1", 1, "nd_a", 10, SHA, StoragePlacement.State.PENDING_REMOTE, NOW)));

		var v = verifier.verify(task(payload("sob_1"), "nd_a"), "");

		assertThat(v.ok()).isFalse();
		assertThat(v.status()).isEqualTo("STORAGE_REPLICA_NOT_CONFIRMED");
	}

	@Test
	void copia_stale_nao_e_copia_confirmada() {
		when(catalog.findById("sob_1")).thenReturn(Optional.of(stored()));
		when(catalog.placements("sob_1")).thenReturn(List.of(
			new StoragePlacement("sob_1", 1, "nd_a", 10, SHA, StoragePlacement.State.STALE, NOW)));

		var v = verifier.verify(task(payload("sob_1"), "nd_a"), "");

		assertThat(v.ok()).isFalse();
	}

	@Test
	void bytes_divergentes_do_medido_recusam_sem_retry() {
		when(catalog.findById("sob_1")).thenReturn(Optional.of(stored()));
		when(catalog.placements("sob_1")).thenReturn(List.of(
			new StoragePlacement("sob_1", 1, "nd_a", 10, SHA, StoragePlacement.State.OK, NOW)));

		// A cópia existe, mas o nó afirma um tamanho que o CP mediu diferente: uma das duas
		// afirmações é falsa e o CP é a autoridadae. Retry daria o mesmo número.
		var v = verifier.verify(task(payload("sob_1"), "nd_a"), "{\"bytes\":99}");

		assertThat(v.ok()).isFalse();
		assertThat(v.status()).isEqualTo("STORAGE_REPLICA_MEASURE_MISMATCH");
		assertThat(v.retryable()).isFalse();
	}

	@Test
	void sha_divergente_do_medido_recusa_sem_retry() {
		when(catalog.findById("sob_1")).thenReturn(Optional.of(stored()));
		when(catalog.placements("sob_1")).thenReturn(List.of(
			new StoragePlacement("sob_1", 1, "nd_a", 10, SHA, StoragePlacement.State.OK, NOW)));

		var v = verifier.verify(task(payload("sob_1"), "nd_a"), "{\"sha256\":\"" + "0".repeat(64) + "\"}");

		assertThat(v.ok()).isFalse();
		assertThat(v.status()).isEqualTo("STORAGE_REPLICA_MEASURE_MISMATCH");
	}

	@Test
	void tarefa_sem_objectId_no_payload_nao_e_conferivel() {
		var v = verifier.verify(task("{\"replica\":1}", "nd_a"), "{}");

		assertThat(v.ok()).isFalse();
		assertThat(v.status()).isEqualTo("STORAGE_TASK_PAYLOAD_INVALID");
		// Malformada: tentar de novo manda o mesmo payload.
		assertThat(v.retryable()).isFalse();
	}
}