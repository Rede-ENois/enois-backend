package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.time.Instant;
import java.time.LocalDate;

import com.socialcoisa.rede.profile.domain.Experience;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_experiences", indexes = @Index(name = "idx_user_experiences_user", columnList = "user_id"))
class ExperienceJpaEntity {

	@Id
	private String id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false)
	private String company;

	private String location;

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

	protected ExperienceJpaEntity() {
	}

	static ExperienceJpaEntity fromDomain(Experience experience) {
		ExperienceJpaEntity e = new ExperienceJpaEntity();
		e.id = experience.id();
		e.userId = experience.userId();
		e.title = experience.title();
		e.company = experience.company();
		e.location = experience.location();
		e.description = experience.description();
		e.currentlyHere = experience.currentlyHere();
		e.startDate = experience.startDate();
		e.endDate = experience.endDate();
		e.displayOrder = experience.displayOrder();
		e.createdAt = experience.createdAt();
		e.updatedAt = experience.updatedAt();
		return e;
	}

	Experience toDomain() {
		return Experience.restore(id, userId, title, company, location, description, currentlyHere, startDate, endDate,
			displayOrder, createdAt, updatedAt);
	}

}
