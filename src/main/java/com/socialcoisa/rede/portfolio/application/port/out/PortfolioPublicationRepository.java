package com.socialcoisa.rede.portfolio.application.port.out;

import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.portfolio.domain.PortfolioPublication;

/** A trilha de publicações: um retrato por versão publicada. */
public interface PortfolioPublicationRepository {

	Optional<PortfolioPublication> findByVersion(String userId, int version);

	Optional<PortfolioPublication> findLatest(String userId);

	/**
	 * O HTML da versão publicada pedida de cada dono, por dono e por versão. É o que a lista pública
	 * lê para o título: só o que está no ar, nunca o rascunho privado.
	 *
	 * <p>{@code live} é o PAR dono → versão de cada página. Receber os donos soltos carrega o
	 * histórico inteiro de cada um (html e peças são {@code longtext}) para a leitura usar uma linha.</p>
	 */
	Map<String, Map<Integer, String>> publishedHtml(Map<String, Integer> live);

	PortfolioPublication save(PortfolioPublication publication);
}
