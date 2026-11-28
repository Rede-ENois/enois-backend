package com.socialcoisa.rede.portfolio.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.portfolio.application.port.out.PortfolioPageRepository;
import com.socialcoisa.rede.portfolio.application.port.out.PortfolioPublicationRepository;
import com.socialcoisa.rede.portfolio.domain.PortfolioPage;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;

/**
 * A lista pública das vitrines que estão no ar — o que a seção de vitrines do Explorar mostra.
 *
 * <p>Três regras governam a lista. Só entra quem publicou (versão 1 em diante): rascunho nunca
 * aparece. Conta banida ou removida sai, como na leitura por username. E a página vem junto apenas
 * para o título da lista — sempre a versão no ar, a mesma que {@code GET /portfolio/{username}}
 * serve, nunca o rascunho que o dono está montando.</p>
 *
 * <p>A paginação é por chave {@code (publishedAt, userId)}, não por posição: um offset pularia ou
 * repetiria linhas quando alguém publica entre duas páginas. O cursor anda pela última linha LIDA do
 * banco — inclusive quando ela foi filtrada —, então ninguém some nem se repete entre páginas.</p>
 */
@Service
public class PublishedPortfolioCatalog {

	static final int DEFAULT_LIMIT = 20;
	static final int MAX_LIMIT = 50;

	private final PortfolioPageRepository pages;
	private final PortfolioPublicationRepository publications;
	private final UserProfileRepository profiles;
	private final SanctionGateReader sanctionGate;

	PublishedPortfolioCatalog(PortfolioPageRepository pages, PortfolioPublicationRepository publications,
			UserProfileRepository profiles, SanctionGateReader sanctionGate) {
		this.pages = pages;
		this.publications = publications;
		this.profiles = profiles;
		this.sanctionGate = sanctionGate;
	}

	/**
	 * Uma página da lista, mais recente primeiro. Cursor nulo começa do início; {@code beforeId}
	 * desempata publicações do mesmo instante.
	 */
	@Transactional(readOnly = true)
	public Page page(Instant before, String beforeId, int limit) {
		List<PortfolioPage> published = pages.findPublished(before, beforeId, Math.clamp(limit, 1, MAX_LIMIT));
		if (published.isEmpty()) {
			return new Page(List.of(), null);
		}
		Set<String> banned = sanctionGate.bannedAmong(published.stream().map(PortfolioPage::userId).toList());
		Map<String, Map<Integer, String>> publishedHtml = publications
			.publishedHtml(published.stream().map(PortfolioPage::userId).toList());
		List<PublishedPortfolio> visible = new ArrayList<>();
		for (PortfolioPage page : published) {
			if (banned.contains(page.userId())) {
				continue;
			}
			// A lista e a página do mesmo dono mostram o mesmo título: sai do documento publicado.
			String liveHtml = publishedHtml.getOrDefault(page.userId(), Map.of()).get(page.publishedVersion());
			profiles.findById(page.userId())
				.filter(profile -> shown(profile.username()))
				.ifPresent(profile -> visible.add(new PublishedPortfolio(profile.id(), profile.username(),
					profile.name(), profile.avatar(), page.publishedVersion(), page.publishedAt(),
					PortfolioTitles.titleOf(liveHtml))));
		}
		PortfolioPage last = published.get(published.size() - 1);
		return new Page(visible, new Key(last.publishedAt(), last.userId()));
	}

	/** Conta removida (anônima, `removed-`) nunca aparece na lista. */
	private static boolean shown(String username) {
		return StringUtils.hasText(username) && !username.startsWith(UserProfile.REMOVED_USERNAME_PREFIX);
	}

	/** Uma página da lista e a chave da última linha lida do banco (base do próximo cursor). */
	public record Page(List<PublishedPortfolio> items, Key next) {
	}

	/** Posição na ordenação pública: quando publicou e, no empate, de quem é a vitrine. */
	public record Key(Instant publishedAt, String userId) {
	}

	/** Uma vitrine da lista: quem é o dono, a versão no ar e o título da página. */
	public record PublishedPortfolio(String userId, String username, String name, String avatarUrl, int version,
			Instant publishedAt, String title) {
	}
}