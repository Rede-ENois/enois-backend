package com.socialcoisa.rede.calls.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.calls.domain.CallKind;
import com.socialcoisa.rede.calls.domain.CallModality;
import com.socialcoisa.rede.calls.domain.CallSession;
import com.socialcoisa.rede.calls.domain.CallState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "call_sessions")
class CallSessionJpaEntity {

	@Id
	private String id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private CallKind kind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private CallModality modality;

	@Column(name = "conversation_id")
	private String conversationId;

	@Column(name = "group_id")
	private String groupId;

	@Column(name = "initiator_id", nullable = false)
	private String initiatorId;

	@Column(name = "room_id", nullable = false, length = 64)
	private String roomId;

	@Column(name = "display_name", nullable = false, length = 120)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private CallState state;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "answered_at")
	private Instant answeredAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@Column(name = "ended_by")
	private String endedBy;

	@Column(name = "end_reason", length = 32)
	private String endReason;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "last_activity_at", nullable = false)
	private Instant lastActivityAt;

	protected CallSessionJpaEntity() {
	}

	static CallSessionJpaEntity from(CallSession call) {
		CallSessionJpaEntity entity = new CallSessionJpaEntity();
		entity.copy(call);
		return entity;
	}

	void copy(CallSession call) {
		this.id = call.id();
		this.kind = call.kind();
		this.modality = call.modality();
		this.conversationId = call.conversationId();
		this.groupId = call.groupId();
		this.initiatorId = call.initiatorId();
		this.roomId = call.roomId();
		this.displayName = call.displayName();
		this.state = call.state();
		this.createdAt = call.createdAt();
		this.answeredAt = call.answeredAt();
		this.endedAt = call.endedAt();
		this.endedBy = call.endedBy();
		this.endReason = call.endReason();
		this.expiresAt = call.expiresAt();
		this.lastActivityAt = call.lastActivityAt();
	}

	CallSession toDomain() {
		return new CallSession(id, kind, modality, conversationId, groupId, initiatorId, roomId, displayName, state,
			createdAt, answeredAt, endedAt, endedBy, endReason, expiresAt, lastActivityAt);
	}

	String id() {
		return id;
	}

	String conversationId() {
		return conversationId;
	}

	String groupId() {
		return groupId;
	}

	CallState state() {
		return state;
	}

	Instant createdAt() {
		return createdAt;
	}
}
