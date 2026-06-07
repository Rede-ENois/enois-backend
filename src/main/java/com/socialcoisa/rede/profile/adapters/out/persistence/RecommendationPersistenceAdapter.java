package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.profile.application.port.out.RecommendationRepository;
import com.socialcoisa.rede.profile.domain.Recommendation;

@Repository
class RecommendationPersistenceAdapter implements RecommendationRepository {

	private final SpringDataRecommendationJpaRepository repo;

	RecommendationPersistenceAdapter(SpringDataRecommendationJpaRepository repo) {
		this.repo = repo;
	}

	@Override
	public Recommendation save(Recommendation recommendation) {
		return repo.save(RecommendationJpaEntity.fromDomain(recommendation)).toDomain();
	}

	@Override
	public Optional<Recommendation> findById(String id) {
		return repo.findById(id).map(RecommendationJpaEntity::toDomain);
	}

	@Override
	public List<Recommendation> findByRecommendedId(String recommendedId) {
		return repo.findByRecommendedIdOrderByCreatedAtDesc(recommendedId).stream()
			.map(RecommendationJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Recommendation> findByRecommenderId(String recommenderId) {
		return repo.findByRecommenderIdOrderByCreatedAtDesc(recommenderId).stream()
			.map(RecommendationJpaEntity::toDomain)
			.toList();
	}

	@Override
	public boolean existsByRecommenderAndRecommended(String recommenderId, String recommendedId) {
		return repo.existsByRecommenderIdAndRecommendedId(recommenderId, recommendedId);
	}

	@Override
	public void delete(String id) {
		repo.deleteById(id);
	}
}
