package com.socialcoisa.rede.portfolio.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.portfolio.domain.PortfolioPage;

/** O estado editável da página do portfólio, uma linha por dono. */
public interface PortfolioPageRepository {

	Optional<PortfolioPage> find(String userId);

	PortfolioPage save(PortfolioPage page);

	/**
	 * As últimas publicações, mais recentes primeiro, para a lista pública. Só linhas já publicadas
	 * (versão 1 em diante) e depois da chave {@code (before, beforeId)} — a data e, no empate, o dono.
	 */
	List<PortfolioPage> findPublished(Instant before, String beforeId, int limit);
}
