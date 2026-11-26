package com.socialcoisa.rede.portfolio.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.portfolio.domain.PortfolioBlock;

/** Os blocos do portfólio do dono, na ordem em que ele montou. */
public interface PortfolioRepository {

	List<PortfolioBlock> findBlocks(String userId);

	Optional<PortfolioBlock> findBlock(String userId, String blockId);

	PortfolioBlock saveBlock(PortfolioBlock block);

	void deleteBlock(String userId, String blockId);
}
