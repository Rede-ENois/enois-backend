package com.socialcoisa.rede.profile.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.profile.application.port.out.RecommendationRepository;
import com.socialcoisa.rede.profile.domain.Recommendation;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class RecommendationService {

	private final RecommendationRepository repository;
	private final com.socialcoisa.rede.shared.security.ActionThrottle actionThrottle;
	private final int recommendationsPerDay;
	private final com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate;
	private final Clock clock;

	public RecommendationService(RecommendationRepository repository,
			com.socialcoisa.rede.shared.security.ActionThrottle actionThrottle,
			@org.springframework.beans.factory.annotation.Value("${rede.profile.recommendations-per-day:20}") int recommendationsPerDay,
			com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate, Clock clock) {
		this.sanctionGate = sanctionGate;
		this.repository = repository;
		this.actionThrottle = actionThrottle;
		this.recommendationsPerDay = recommendationsPerDay;
		this.clock = clock;
	}

	@Transactional
	public Recommendation recommend(String recommenderId, String recommendedId, String comment) {
		// Teto por conta: recomendar em massa (para um anel ou por retaliação) deixa de ser
		// gratuito. O limite é de FREQUÊNCIA — quem recomenda de boa-fé não chega perto.
		actionThrottle.exigePermitido("recommendation", recommenderId, recommendationsPerDay,
			java.time.Duration.ofDays(1));
		if (recommenderId.equals(recommendedId)) {
			throw new ApiException(400, "SELF_RECOMMENDATION", "Voce nao pode recomendar a si mesmo.");
		}
		if (repository.existsByRecommenderAndRecommended(recommenderId, recommendedId)) {
			throw new ApiException(409, "ALREADY_RECOMMENDED", "Voce ja recomendou este usuario.");
		}
		String id = "rec_" + UUID.randomUUID().toString().replace("-", "");
		Recommendation rec = Recommendation.create(id, recommenderId, recommendedId, comment, Instant.now(clock));
		return repository.save(rec);
	}

	@Transactional
	public void remove(String recommenderId, String recommendedId) {
		var recs = repository.findByRecommenderId(recommenderId).stream()
			.filter(r -> r.recommendedId().equals(recommendedId))
			.toList();
		if (recs.isEmpty()) {
			throw new ApiException(404, "RECOMMENDATION_NOT_FOUND", "Recomendacao nao encontrada.");
		}
		repository.delete(recs.get(0).id());
	}

	@Transactional(readOnly = true)
	public List<Recommendation> listReceived(String userId) {
		return visible(userId, repository.findByRecommendedId(userId), Recommendation::recommenderId);
	}

	@Transactional(readOnly = true)
	public List<Recommendation> listGiven(String userId) {
		return visible(userId, repository.findByRecommenderId(userId), Recommendation::recommendedId);
	}

	/**
	 * Public read of someone's recommendations: a banned account has no public page, and a banned
	 * account on the other side of a recommendation is left out (same rule as the profile read).
	 */
	private List<Recommendation> visible(String userId, List<Recommendation> recommendations,
			java.util.function.Function<Recommendation, String> otherSide) {
		if (sanctionGate.banned(userId)) {
			throw new com.socialcoisa.rede.shared.web.ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado.");
		}
		java.util.Set<String> banned = sanctionGate.bannedAmong(recommendations.stream().map(otherSide).toList());
		return recommendations.stream().filter(item -> !banned.contains(otherSide.apply(item))).toList();
	}

	@Transactional(readOnly = true)
	public int countReceived(String userId) {
		return repository.findByRecommendedId(userId).size();
	}
}
