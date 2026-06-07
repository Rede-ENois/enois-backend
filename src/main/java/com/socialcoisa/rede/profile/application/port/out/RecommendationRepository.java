package com.socialcoisa.rede.profile.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.profile.domain.Recommendation;

public interface RecommendationRepository {

	Recommendation save(Recommendation recommendation);

	Optional<Recommendation> findById(String id);

	List<Recommendation> findByRecommendedId(String recommendedId);

	List<Recommendation> findByRecommenderId(String recommenderId);

	boolean existsByRecommenderAndRecommended(String recommenderId, String recommendedId);

	void delete(String id);
}
