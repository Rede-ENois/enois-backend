package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.Optional;

import com.socialcoisa.rede.network.domain.ContentKind;
import com.socialcoisa.rede.network.domain.ContentVersion;

/** Versão do conteúdo público (V279) e o que cada nó de borda já sincronizou. */
public interface ContentVersionRepository {

	Optional<ContentVersion> find(ContentKind kind, String contentId);

	/**
	 * Grava a versão nova. A condição é a versão esperada: duas instâncias que calcularam o mesmo
	 * corpo ao mesmo tempo não cunham duas versões — a segunda não encontra a linha na versão
	 * esperada e não escreve.
	 *
	 * @return true se gravou
	 */
	boolean save(ContentVersion version, long expectedVersion);

	/** O que o nó já tem deste agregado; vazio = nunca sincronizou. */
	Optional<NodeSnapshot> snapshot(String nodeId, ContentKind kind, String contentId);

	/** Registra o que o nó baixou. Reenvio do mesmo conteúdo atualiza a hora, não a versão. */
	void recordSnapshot(String nodeId, ContentKind kind, String contentId, long sourceVersion, String contentHash,
			Instant now);

	/** Quantos agregados deste tipo o nó tem em atraso (versão do nó menor que a da origem). */
	long countBehind(String nodeId, ContentKind kind);

	/** @param contentHash sha-256 hex do corpo servido, para a reconciliação comparar byte a byte */
	record NodeSnapshot(String nodeId, ContentKind kind, String contentId, long sourceVersion, String contentHash,
			Instant syncedAt) {
	}
}
