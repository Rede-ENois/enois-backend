package com.socialcoisa.rede.portfolio.adapters.out.persistence;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.portfolio.application.port.out.PortfolioPublicationRepository;
import com.socialcoisa.rede.portfolio.domain.PortfolioPublication;

@Repository
class PortfolioPublicationAdapter implements PortfolioPublicationRepository {

	private final SpringDataPortfolioPublicationJpaRepository jpaRepository;

	PortfolioPublicationAdapter(SpringDataPortfolioPublicationJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public Optional<PortfolioPublication> findByVersion(String userId, int version) {
		return jpaRepository.findByUserIdAndVersion(userId, version).map(PortfolioPublicationJpaEntity::toDomain);
	}

	@Override
	public Optional<PortfolioPublication> findLatest(String userId) {
		return jpaRepository.findFirstByUserIdOrderByVersionDesc(userId)
			.map(PortfolioPublicationJpaEntity::toDomain);
	}

	@Override
	public PortfolioPublication save(PortfolioPublication publication) {
		return jpaRepository.save(PortfolioPublicationJpaEntity.fromDomain(publication)).toDomain();
	}
}
