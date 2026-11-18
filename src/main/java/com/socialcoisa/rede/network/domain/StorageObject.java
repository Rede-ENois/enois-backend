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
 *
 * <p><b>Versões (brief §14, V352).</b> Cada versão de uma chave é um objeto com {@code id} próprio:
 * o {@code id} que o cliente usa continua apontando para o conteúdo que ele gravou, e a versão
 * nova não herda esse nome. {@code supersededBy} nulo é a versão <b>corrente</b> — a única que o
 * bucket resolve por chave. {@code expiresAt} nulo é "não expira".</p>
 */
public record StorageObject(String id, String bucket, String key, String contentType, Long bytes,
		String sha256, StorageObjectState state, String createdBy, Instant createdAt, Instant updatedAt,
		Instant deletedAt, long version, String supersededBy, Instant expiresAt) {

	/** Objeto sem versionamento nem prazo: o formato de tudo que existia antes do V352. */
	public static StorageObject current(String id, String bucket, String key, String contentType, Long bytes,
			String sha256, StorageObjectState state, String createdBy, Instant createdAt, Instant updatedAt,
			Instant deletedAt) {
		return new StorageObject(id, bucket, key, contentType, bytes, sha256, state, createdBy, createdAt,
			updatedAt, deletedAt, 1L, null, null);
	}

	/** O objeto tem cópia inteira e conferida por algum nó? Só então o download é possível. */
	public boolean servable() {
		return state.downloadable() && bytes != null && sha256 != null;
	}

	/** É a versão que a chave resolve hoje? Versão substituída não é apagada — está no histórico. */
	public boolean currentVersion() {
		return supersededBy == null;
	}

	/** Venceu: a lápide ainda não veio, mas o objeto não deveria mais ser servível. */
	public boolean expired(Instant now) {
		return expiresAt != null && !expiresAt.isAfter(now);
	}

	/** O nome completo que o cliente S3 usa ({@code bucket/key}), para log e auditoria. */
	public String path() {
		return bucket + "/" + key;
	}

	/** O nome completo com a versão, para log e auditoria de versão. */
	public String versionPath() {
		return path() + "#v" + version;
	}
}