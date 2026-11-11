package com.socialcoisa.rede.network.domain;

import java.time.Instant;

/**
 * Versão de um agregado público: o {@code sourceVersion} que o nó de borda serve junto com o
 * conteúdo, e o hash do corpo que essa versão representa.
 *
 * <p>É contador, não carimbo de tempo: dois commits no mesmo milissegundo, ou um efeito que muda o
 * que a borda mostra sem tocar a linha do agregado, mudariam o conteúdo sem mudar a hora. O nó que
 * compara versão precisa ver a diferença.</p>
 *
 * <p>O hash é o que decide se a versão sobe. Recalcular a projeção e obter o mesmo corpo não é
 * mudança: subir a versão ali faria todo nó baixar de novo o que não mudou, e faria a reconciliação
 * acusar divergência onde não há. Versão nova significa corpo novo.</p>
 *
 * @param kind tipo do conteúdo (a allowlist)
 * @param contentId id ou chave estável do agregado (o @ no perfil, o id na comunidade)
 * @param version contador monotônico; começa em 1 e só sobe
 * @param contentHash sha-256 hex do corpo servido nesta versão
 * @param updatedAt quando a versão foi cunhada
 */
public record ContentVersion(ContentKind kind, String contentId, long version, String contentHash,
		Instant updatedAt) {

	public ContentVersion {
		if (kind == null) {
			throw new IllegalArgumentException("ContentKind nulo.");
		}
		if (contentId == null || contentId.isBlank()) {
			throw new IllegalArgumentException("contentId vazio.");
		}
		if (version < 1) {
			throw new IllegalArgumentException("Versao de conteudo comeca em 1.");
		}
		if (contentHash == null || contentHash.isBlank()) {
			throw new IllegalArgumentException("Hash de conteudo vazio.");
		}
	}

	/** O corpo desta versão é o mesmo do hash informado? Então não há versão nova a cunhar. */
	public boolean sameBody(String hash) {
		return contentHash.equals(hash);
	}

	public ContentVersion next(String hash, Instant now) {
		return new ContentVersion(kind, contentId, version + 1, hash, now);
	}
}
