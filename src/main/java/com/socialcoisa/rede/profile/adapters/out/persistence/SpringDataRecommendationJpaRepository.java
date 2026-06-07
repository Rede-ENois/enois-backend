package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataRecommendationJpaRepository extends JpaRepository<RecommendationJpaEntity, String> {

	List<RecommendationJpaEntity> findByRecommendedIdOrderByCreatedAtDesc(String recommendedId);

	List<RecommendationJpaEntity> findByRecommenderIdOrderByCreatedAtDesc(String recommenderId);

	boolean existsByRecommenderIdAndRecommendedId(String recommenderId, String recommendedId);
}
