package com.socialcoisa.rede.portfolio.domain;

import java.time.Instant;

/**
 * Uma peça do portfólio, na ordem em que o dono montou.
 *
 * <p>{@code publishedVersion} é o que separa a peça do resto do mundo: {@code null} significa
 * que ela ainda nunca foi publicada. Alterar uma peça publicada zera o campo de novo até a
 * próxima publicação, então o público continua vendo a última versão que o dono confirmou.</p>
 */
public record PortfolioBlock(String id, String userId, int position, PortfolioBlockType type, String title,
		String text, String projectId, String mediaId, String linkUrl, Integer publishedVersion,
		Instant createdAt, Instant updatedAt) {

	public boolean published() {
		return publishedVersion != null;
	}

	/** Copia com o bloco marcado como pertencente à versão recém-publicada. */
	public PortfolioBlock atVersion(int version) {
		return new PortfolioBlock(id, userId, position, type, title, text, projectId, mediaId, linkUrl, version,
			createdAt, updatedAt);
	}

	/** Copia com o bloco fora do publicado (edição depois da última publicação). */
	public PortfolioBlock unpublished() {
		return new PortfolioBlock(id, userId, position, type, title, text, projectId, mediaId, linkUrl, null,
			createdAt, updatedAt);
	}
}
