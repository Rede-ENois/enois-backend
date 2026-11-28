package com.socialcoisa.rede.portfolio.application.port.out;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.portfolio.domain.PortfolioPublication;

/** A trilha de publicações: um retrato por versão publicada. */
public interface PortfolioPublicationRepository {

	Optional<PortfolioPublication> findByVersion(String userId, int version);

	Optional<PortfolioPublication> findLatest(String userId);

	/**
	 * O HTML de cada versão publicada dos donos informados, por dono e por versão. É o que a lista
	 * pública lê para o título: só o que está no ar, nunca o rascunho privado.
	 */
	Map<String, Map<Integer, String>> publishedHtml(Collection<String> userIds);

	PortfolioPublication save(PortfolioPublication publication);
}
