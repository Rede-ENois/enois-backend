package com.socialcoisa.rede.mentoring.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.mentoring.domain.MentorSlot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "mentor_slots", indexes = @Index(name = "idx_mentor_slots_mentor", columnList = "mentor_id"))
class MentorSlotJpaEntity {

	@Id
	private String id;

	@Column(name = "mentor_id", nullable = false)
	private String mentorId;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	@Column(nullable = false)
	private boolean available;

	protected MentorSlotJpaEntity() {
	}

	static MentorSlotJpaEntity fromDomain(MentorSlot slot) {
		MentorSlotJpaEntity e = new MentorSlotJpaEntity();
		e.id = slot.id();
		e.mentorId = slot.mentorId();
		e.startsAt = slot.startsAt();
		e.endsAt = slot.endsAt();
		e.available = slot.available();
		return e;
	}

	MentorSlot toDomain() {
		return MentorSlot.restore(id, mentorId, startsAt, endsAt, available);
	}

}
