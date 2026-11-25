package com.socialcoisa.rede.portfolio.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.portfolio.domain.PortfolioPublication;

/** A trilha de publicações: um retrato por versão publicada. */
public interface PortfolioPublicationRepository {

	Optional<PortfolioPublication> findByVersion(String userId, int version);

	Optional<PortfolioPublication> findLatest(String userId);

	PortfolioPublication save(PortfolioPublication publication);
}
