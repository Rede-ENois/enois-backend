package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;

import com.socialcoisa.rede.profile.application.port.out.RecommendationRepository;
import com.socialcoisa.rede.profile.domain.Recommendation;

@Repository
class RecommendationPersistenceAdapter implements RecommendationRepository {

	private final SpringDataRecommendationJpaRepository repo;
	private final EntityManager em;

	RecommendationPersistenceAdapter(SpringDataRecommendationJpaRepository repo, EntityManager em) {
		this.repo = repo;
		this.em = em;
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
	public int countReciprocal(String recommendedId) {
		// Recebidas em que existe a volta (eu recomendei quem me recomendou): par mútuo = troca,
		// não confiança — não conta para a credibilidade.
		var result = repo.countReciprocal(recommendedId);
		return ((Number) result).intValue();
	}

	@Override
	public void delete(String id) {
		repo.deleteById(id);
	}

	@Override
	public Map<String, Integer> receivedCounts(Collection<String> ids) {
		return grouped(ids, "select recommended_id, count(*) from user_recommendations"
			+ " where recommended_id in (:ids) group by recommended_id");
	}

	@Override
	public Map<String, Integer> reciprocalCounts(Collection<String> ids) {
		// Recebidas que têm a volta (eu recomendei quem me recomendou): par mútuo = troca.
		return grouped(ids, "select r.recommended_id, count(*) from user_recommendations r"
			+ " where r.recommended_id in (:ids) and exists (select 1 from user_recommendations x"
			+ " where x.recommender_id = r.recommended_id and x.recommended_id = r.recommender_id)"
			+ " group by r.recommended_id");
	}

	private Map<String, Integer> grouped(Collection<String> ids, String sql) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		@SuppressWarnings("unchecked")
		List<Object[]> rows = em.createNativeQuery(sql).setParameter("ids", ids).getResultList();
		var counts = new java.util.HashMap<String, Integer>();
		for (Object[] row : rows) {
			counts.put((String) row[0], ((Number) row[1]).intValue());
		}
		return counts;
	}
}
