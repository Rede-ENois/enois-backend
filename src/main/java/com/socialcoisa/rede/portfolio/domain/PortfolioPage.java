package com.socialcoisa.rede.portfolio.domain;

import java.time.Instant;

/**
 * O estado editável da página do portfólio.
 *
 * <p>{@code draftHtml} é o que o dono está montando; {@code publishedVersion} diz qual versão está
 * no ar ({@code 0} = nunca publicado). O conteúdo das versões publicadas vive em
 * {@link PortfolioPublication}, para "desfazer" poder existir sem reescrever o rascunho.</p>
 */
public record PortfolioPage(String userId, String draftHtml, int publishedVersion, Instant publishedAt,
		Instant updatedAt) {

	public boolean published() {
		return publishedVersion > 0;
	}

	public PortfolioPage withDraft(String draftHtml, Instant now) {
		return new PortfolioPage(userId, draftHtml, publishedVersion, publishedAt, now);
	}

	public PortfolioPage publishedAs(int version, Instant at) {
		return new PortfolioPage(userId, draftHtml, version, at, at);
	}
}
