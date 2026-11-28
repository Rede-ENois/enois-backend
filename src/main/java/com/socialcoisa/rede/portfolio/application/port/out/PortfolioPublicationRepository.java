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
	 * O HTML de cada versão publicada pedida, por dono e por versão. É o que a lista pública lê
	 * para o título: só o que está no ar, nunca o rascunho privado.
	 *
	 * <p>{@code live} é o PAR dono → versão de cada página da lista. Receber donos e versões como
	 * dois conjuntos independentes faz a consulta devolver o produto cartesiano — o dono A com uma
	 * versão que só o dono B usa —, linhas que a leitura descarta mas que o banco lê e o driver
	 * carrega (html e peças são {@code longtext}). Com o par, a consulta pede exatamente o que a
	 * página vai ler.</p>
	 */
	Map<String, Map<Integer, String>> publishedHtml(Map<String, Integer> live);

	PortfolioPublication save(PortfolioPublication publication);
}
