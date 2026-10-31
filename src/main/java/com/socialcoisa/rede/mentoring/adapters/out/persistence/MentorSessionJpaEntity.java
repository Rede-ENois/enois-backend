package com.socialcoisa.rede.mentoring.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.mentoring.domain.MentorSession;
import com.socialcoisa.rede.mentoring.domain.RoomMode;
import com.socialcoisa.rede.mentoring.domain.SessionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "mentor_sessions", indexes = {
		@Index(name = "idx_mentor_sessions_mentor", columnList = "mentor_id"),
		@Index(name = "idx_mentor_sessions_mentee", columnList = "mentee_id") })
class MentorSessionJpaEntity {

	@Id
	private String id;

	@Column(name = "mentor_id", nullable = false)
	private String mentorId;

	@Column(name = "mentee_id", nullable = false)
	private String menteeId;

	@Column(name = "slot_id")
	private String slotId;

	@Column(length = 1000)
	private String message;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SessionStatus status;

	@Column(name = "price_sparks")
	private Long priceSparks;

	@Column(name = "room_mode", length = 24)
	private String roomMode;

	@Column(name = "room_url", length = 500)
	private String roomUrl;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected MentorSessionJpaEntity() {
	}

	static MentorSessionJpaEntity fromDomain(MentorSession session) {
		MentorSessionJpaEntity e = new MentorSessionJpaEntity();
		e.id = session.id();
		e.mentorId = session.mentorId();
		e.menteeId = session.menteeId();
		e.slotId = session.slotId();
		e.message = session.message();
		e.status = session.status();
		e.priceSparks = session.priceSparks();
		e.roomMode = session.roomMode() == null ? RoomMode.JITSI_SESSION.name() : session.roomMode().name();
		e.roomUrl = session.roomUrl();
		e.createdAt = session.createdAt();
		return e;
	}

	MentorSession toDomain() {
		return MentorSession.restore(id, mentorId, menteeId, slotId, message, status, priceSparks,
			RoomMode.fromStored(roomMode), roomUrl, createdAt);
	}

}
