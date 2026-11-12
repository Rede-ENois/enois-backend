package com.socialcoisa.rede.profile.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.profile.domain.Recommendation;

public interface RecommendationRepository {

	Recommendation save(Recommendation recommendation);

	Optional<Recommendation> findById(String id);

	List<Recommendation> findByRecommendedId(String recommendedId);

	List<Recommendation> findByRecommenderId(String recommenderId);

	boolean existsByRecommenderAndRecommended(String recommenderId, String recommendedId);

	/**
	 * Recomendações recebidas que são RECÍPROCAS (a pessoa também recomendou quem a recomendou).
	 * Elas não contam para a credibilidade: par mútuo é troca, não confiança.
	 */
	int countReciprocal(String recommendedId);

	void delete(String id);

	/**
	 * Recomendações recebidas de cada conta do conjunto, em uma consulta (GROUP BY no recomendado)
	 * — o job do ranking só precisa do NÚMERO, não das entidades.
	 */
	Map<String, Integer> receivedCounts(Collection<String> ids);

	/**
	 * Recomendações recebidas RECÍPROCAS de cada conta do conjunto, em uma consulta: par mútuo
	 * é troca, não confiança — não conta para a credibilidade.
	 */
	Map<String, Integer> reciprocalCounts(Collection<String> ids);
}
