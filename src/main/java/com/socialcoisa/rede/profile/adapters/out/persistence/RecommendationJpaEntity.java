package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.profile.domain.Recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "user_recommendations", uniqueConstraints = @UniqueConstraint(
	name = "uk_recommendation_pair", columnNames = { "recommender_id", "recommended_id" }),
	indexes = @Index(name = "idx_recommendations_recommended", columnList = "recommended_id"))
class RecommendationJpaEntity {

	@Id
	private String id;

	@Column(name = "recommender_id", nullable = false)
	private String recommenderId;

	@Column(name = "recommended_id", nullable = false)
	private String recommendedId;

	@Column(length = 500)
	private String comment;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected RecommendationJpaEntity() {}

	static RecommendationJpaEntity fromDomain(Recommendation r) {
		RecommendationJpaEntity e = new RecommendationJpaEntity();
		e.id = r.id();
		e.recommenderId = r.recommenderId();
		e.recommendedId = r.recommendedId();
		e.comment = r.comment();
		e.createdAt = r.createdAt();
		return e;
	}

	Recommendation toDomain() {
		return Recommendation.restore(id, recommenderId, recommendedId, comment, createdAt);
	}
}
