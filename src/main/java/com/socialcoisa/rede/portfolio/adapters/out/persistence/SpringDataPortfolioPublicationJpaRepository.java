package com.socialcoisa.rede.portfolio.adapters.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPortfolioPublicationJpaRepository extends JpaRepository<PortfolioPublicationJpaEntity, String> {

	Optional<PortfolioPublicationJpaEntity> findByUserIdAndVersion(String userId, int version);

	Optional<PortfolioPublicationJpaEntity> findFirstByUserIdOrderByVersionDesc(String userId);
}
