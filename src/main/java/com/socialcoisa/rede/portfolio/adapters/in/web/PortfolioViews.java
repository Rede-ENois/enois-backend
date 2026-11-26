package com.socialcoisa.rede.portfolio.adapters.in.web;

import java.util.List;

import com.socialcoisa.rede.portfolio.application.PortfolioService.OwnPortfolio;
import com.socialcoisa.rede.portfolio.application.PortfolioService.PublicPortfolio;
import com.socialcoisa.rede.portfolio.application.PublishedPortfolioCatalog.PublishedPortfolio;
import com.socialcoisa.rede.portfolio.domain.PortfolioBlock;
import com.socialcoisa.rede.portfolio.domain.PortfolioPage;

/** O que o endpoint devolve ao dono e ao público. */
final class PortfolioViews {

	private PortfolioViews() {
	}

	record BlockView(String id, int position, String type, String title, String text, String projectId,
			String mediaId, String linkUrl, boolean published) {

		static BlockView from(PortfolioBlock block) {
			return new BlockView(block.id(), block.position(), block.type().name(), block.title(), block.text(),
				block.projectId(), block.mediaId(), block.linkUrl(), block.published());
		}
	}

	/** Vista do dono: o rascunho, as peças e a última versão publicada. */
	record Mine(String html, int publishedVersion, String publishedAt, List<BlockView> blocks) {

		static Mine from(OwnPortfolio portfolio) {
			PortfolioPage page = portfolio.page();
			return new Mine(page.draftHtml(), page.publishedVersion(),
				page.publishedAt() == null ? null : page.publishedAt().toString(),
				portfolio.blocks().stream().map(BlockView::from).toList());
		}
	}

	/**
	 * Uma linha da lista pública: quem é o dono, a versão no ar e o título da página. Sem o HTML e
	 * sem as peças — quem abre a vitrine pede o retrato inteiro por username.
	 */
	record Showcase(String username, String name, String avatarUrl, int version, String publishedAt,
			String title) {

		static Showcase from(PublishedPortfolio portfolio) {
			return new Showcase(portfolio.username(), portfolio.name(), portfolio.avatarUrl(),
				portfolio.version(), portfolio.publishedAt().toString(), portfolio.title());
		}
	}

	/** Vista pública: o retrato publicado. */
	record Published(String username, int version, String html, String publishedAt, List<BlockView> blocks) {

		static Published from(PublicPortfolio portfolio) {
			return new Published(portfolio.username(), portfolio.version(), portfolio.html(),
				portfolio.publishedAt().toString(),
				portfolio.blocks().stream().map(BlockView::from).toList());
		}
	}
}
