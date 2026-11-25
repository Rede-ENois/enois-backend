package com.socialcoisa.rede.portfolio.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.portfolio.domain.PortfolioPage;

/** O estado editável da página do portfólio, uma linha por dono. */
public interface PortfolioPageRepository {

	Optional<PortfolioPage> find(String userId);

	PortfolioPage save(PortfolioPage page);
}
