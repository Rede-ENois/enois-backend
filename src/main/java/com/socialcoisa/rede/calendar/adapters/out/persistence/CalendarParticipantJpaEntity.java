package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.calendar.domain.CalendarParticipant;
import com.socialcoisa.rede.calendar.domain.CalendarParticipantState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "calendar_entry_participants", indexes = {
		@Index(name = "idx_calendar_participants_entry", columnList = "entry_id"),
		@Index(name = "idx_calendar_participants_user", columnList = "user_id, state")})
class CalendarParticipantJpaEntity {

	@Id
	private String id;

	@Column(name = "entry_id", nullable = false, length = 255)
	private String entryId;

	@Column(name = "user_id", nullable = false, length = 255)
	private String userId;

	@Column(name = "invited_by", nullable = false, length = 255)
	private String invitedBy;

	@Column(nullable = false, length = 16)
	private String state;

	@Column(name = "invited_at", nullable = false)
	private Instant invitedAt;

	@Column(name = "responded_at")
	private Instant respondedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected CalendarParticipantJpaEntity() {
	}

	static CalendarParticipantJpaEntity fromDomain(CalendarParticipant participant) {
		CalendarParticipantJpaEntity e = new CalendarParticipantJpaEntity();
		e.id = participant.id();
		e.entryId = participant.entryId();
		e.userId = participant.userId();
		e.invitedBy = participant.invitedBy();
		e.state = participant.state().name();
		e.invitedAt = participant.invitedAt();
		e.respondedAt = participant.respondedAt();
		e.updatedAt = participant.updatedAt();
		return e;
	}

	CalendarParticipant toDomain() {
		return new CalendarParticipant(id, entryId, userId, invitedBy, CalendarParticipantState.parse(state),
			invitedAt, respondedAt, updatedAt);
	}

}
