package com.socialcoisa.rede.portfolio.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.portfolio.application.port.out.PortfolioRepository;
import com.socialcoisa.rede.portfolio.domain.PortfolioBlock;

@Repository
class PortfolioBlockPersistenceAdapter implements PortfolioRepository {

	private final SpringDataPortfolioBlockJpaRepository jpaRepository;

	PortfolioBlockPersistenceAdapter(SpringDataPortfolioBlockJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public List<PortfolioBlock> findBlocks(String userId) {
		return jpaRepository.findByUserIdOrderByPositionAscIdAsc(userId).stream()
			.map(PortfolioBlockJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Optional<PortfolioBlock> findBlock(String userId, String blockId) {
		return jpaRepository.findByIdAndUserId(blockId, userId).map(PortfolioBlockJpaEntity::toDomain);
	}

	@Override
	public PortfolioBlock saveBlock(PortfolioBlock block) {
		return jpaRepository.save(PortfolioBlockJpaEntity.fromDomain(block)).toDomain();
	}

	@Override
	public void deleteBlock(String userId, String blockId) {
		jpaRepository.deleteById(blockId);
	}
}
