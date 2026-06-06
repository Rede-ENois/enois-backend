package com.socialcoisa.rede.mentoring.adapters.out.persistence;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.socialcoisa.rede.mentoring.domain.Mentor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "mentors")
class MentorJpaEntity {

	@Id
	private String id;

	@Column(name = "user_id", nullable = false, unique = true)
	private String userId;

	@Column(length = 1000)
	private String expertise;

	@Column(nullable = false)
	private double rating;

	@Column(name = "sessions_count", nullable = false)
	private int sessionsCount;

	private String price;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected MentorJpaEntity() {
	}

	static MentorJpaEntity fromDomain(Mentor mentor) {
		MentorJpaEntity e = new MentorJpaEntity();
		e.id = mentor.id();
		e.userId = mentor.userId();
		e.expertise = String.join(",", mentor.expertise());
		e.rating = mentor.rating();
		e.sessionsCount = mentor.sessionsCount();
		e.price = mentor.price();
		e.createdAt = mentor.createdAt();
		e.updatedAt = mentor.updatedAt();
		return e;
	}

	Mentor toDomain() {
		List<String> expertiseList = expertise != null && !expertise.isBlank()
			? Arrays.asList(expertise.split(","))
			: Collections.emptyList();
		return Mentor.restore(id, userId, expertiseList, rating, sessionsCount, price, createdAt, updatedAt);
	}

}
