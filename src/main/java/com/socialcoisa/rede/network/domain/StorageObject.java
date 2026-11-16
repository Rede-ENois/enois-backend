package com.socialcoisa.rede.network.domain;

import java.time.Instant;

/**
 * Um objeto do catálogo do Énóis Storage (V319).
 *
 * <p>É o objeto <b>lógico</b>: identidade estável, independente de qualquer nó. Onde as cópias
 * estão é {@link StoragePlacement}, e a separação não é organizacional — é o que permite a fase 3
 * replicar e mover cópias sem tocar na identidade que o cliente usa.</p>
 *
 * <p>{@code bytes} e {@code sha256} nulos significam "nenhuma cópia inteira ainda". Não são zero:
 * um upload em andamento não tem tamanho conhecido até o corpo chegar inteiro de algum nó.</p>
 */
public record StorageObject(String id, String bucket, String key, String contentType, Long bytes,
		String sha256, StorageObjectState state, String createdBy, Instant createdAt, Instant updatedAt,
		Instant deletedAt) {

	/** O objeto tem cópia inteira e conferida por algum nó? Só então o download é possível. */
	public boolean servable() {
		return state.downloadable() && bytes != null && sha256 != null;
	}

	/** O nome completo que o cliente S3 usa ({@code bucket/key}), para log e auditoria. */
	public String path() {
		return bucket + "/" + key;
	}
}