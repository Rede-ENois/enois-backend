package com.socialcoisa.rede.calls.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.calls.domain.CallParticipant;
import com.socialcoisa.rede.calls.domain.CallParticipantState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "call_participants")
class CallParticipantJpaEntity {

	@Id
	private String id;

	@Column(name = "call_id", nullable = false)
	private String callId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private CallParticipantState state;

	@Column(name = "invited_at", nullable = false)
	private Instant invitedAt;

	@Column(name = "joined_at")
	private Instant joinedAt;

	@Column(name = "left_at")
	private Instant leftAt;

	@Column(name = "last_seen_at")
	private Instant lastSeenAt;

	protected CallParticipantJpaEntity() {
	}

	static CallParticipantJpaEntity from(CallParticipant participant) {
		CallParticipantJpaEntity entity = new CallParticipantJpaEntity();
		entity.copy(participant);
		return entity;
	}

	void copy(CallParticipant participant) {
		this.id = participant.id();
		this.callId = participant.callId();
		this.userId = participant.userId();
		this.state = participant.state();
		this.invitedAt = participant.invitedAt();
		this.joinedAt = participant.joinedAt();
		this.leftAt = participant.leftAt();
		this.lastSeenAt = participant.lastSeenAt();
	}

	CallParticipant toDomain() {
		return new CallParticipant(id, callId, userId, state, invitedAt, joinedAt, leftAt, lastSeenAt);
	}

	String callId() {
		return callId;
	}

	String userId() {
		return userId;
	}

	CallParticipantState state() {
		return state;
	}
}
