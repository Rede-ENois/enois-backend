package com.socialcoisa.rede.mentoring.adapters.out.persistence;

import com.socialcoisa.rede.mentoring.domain.MentorAvailability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "mentor_availabilities",
		indexes = { @Index(name = "idx_mentor_availabilities_mentor", columnList = "mentor_id") })
class MentorAvailabilityJpaEntity {

	@Id
	private String id;

	@Column(name = "mentor_id", nullable = false)
	private String mentorId;

	@Column(name = "day_of_week", nullable = false)
	private int dayOfWeek;

	@Column(name = "time_of_day", nullable = false, length = 5)
	private String timeOfDay;

	protected MentorAvailabilityJpaEntity() {
	}

	static MentorAvailabilityJpaEntity fromDomain(MentorAvailability domain) {
		MentorAvailabilityJpaEntity e = new MentorAvailabilityJpaEntity();
		e.id = domain.id();
		e.mentorId = domain.mentorId();
		e.dayOfWeek = domain.dayOfWeek();
		e.timeOfDay = domain.timeOfDay();
		return e;
	}

	MentorAvailability toDomain() {
		return new MentorAvailability(id, mentorId, dayOfWeek, timeOfDay);
	}

}
