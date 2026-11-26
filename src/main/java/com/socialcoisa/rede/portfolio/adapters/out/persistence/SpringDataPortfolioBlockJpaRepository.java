package com.socialcoisa.rede.portfolio.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPortfolioBlockJpaRepository extends JpaRepository<PortfolioBlockJpaEntity, String> {

	List<PortfolioBlockJpaEntity> findByUserIdOrderByPositionAscIdAsc(String userId);

	Optional<PortfolioBlockJpaEntity> findByIdAndUserId(String id, String userId);
}
