package com.socialcoisa.rede.companies.adapters.out.persistence;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.socialcoisa.rede.companies.domain.MicroProjectReview;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "micro_project_reviews")
class MicroProjectReviewJpaEntity {

	@Id
	@Column(name = "project_id")
	private String projectId;

	@Column(name = "company_id", nullable = false)
	private String companyId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@JdbcTypeCode(SqlTypes.TINYINT)
	@Column(nullable = false)
	private int rating;

	@Column(length = 500)
	private String text;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected MicroProjectReviewJpaEntity() {
	}

	static MicroProjectReviewJpaEntity fromDomain(MicroProjectReview review) {
		MicroProjectReviewJpaEntity e = new MicroProjectReviewJpaEntity();
		e.projectId = review.projectId();
		e.companyId = review.companyId();
		e.userId = review.userId();
		e.rating = review.rating();
		e.text = review.text();
		e.createdAt = review.createdAt();
		return e;
	}

	MicroProjectReview toDomain() {
		return new MicroProjectReview(projectId, companyId, userId, rating, text, createdAt);
	}
}
