package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.time.Instant;
import java.time.LocalDate;

import com.socialcoisa.rede.profile.domain.Education;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_education", indexes = @Index(name = "idx_user_education_user", columnList = "user_id"))
class EducationJpaEntity {

	@Id
	private String id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(nullable = false)
	private String school;

	@Column(nullable = false)
	private String degree;

	private String field;

	@Column(columnDefinition = "text")
	private String description;

	@Column(name = "currently_here", nullable = false)
	private boolean currentlyHere;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "end_date")
	private LocalDate endDate;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected EducationJpaEntity() {
	}

	static EducationJpaEntity fromDomain(Education education) {
		EducationJpaEntity e = new EducationJpaEntity();
		e.id = education.id();
		e.userId = education.userId();
		e.school = education.school();
		e.degree = education.degree();
		e.field = education.field();
		e.description = education.description();
		e.currentlyHere = education.currentlyHere();
		e.startDate = education.startDate();
		e.endDate = education.endDate();
		e.displayOrder = education.displayOrder();
		e.createdAt = education.createdAt();
		e.updatedAt = education.updatedAt();
		return e;
	}

	Education toDomain() {
		return Education.restore(id, userId, school, degree, field, description, currentlyHere, startDate, endDate,
			displayOrder, createdAt, updatedAt);
	}

}
