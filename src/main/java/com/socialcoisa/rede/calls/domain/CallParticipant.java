package com.socialcoisa.rede.calls.domain;

import java.time.Instant;

/** Uma pessoa convidada para a chamada e o que ela fez até agora. */
public record CallParticipant(String id, String callId, String userId, CallParticipantState state,
		Instant invitedAt, Instant joinedAt, Instant leftAt, Instant lastSeenAt) {

	public static CallParticipant of(String id, String callId, String userId, CallParticipantState state,
			Instant now) {
		return new CallParticipant(id, callId, userId, state, now, null, null, null);
	}

	public CallParticipant joined(Instant now) {
		return new CallParticipant(id, callId, userId, CallParticipantState.JOINED, invitedAt,
			joinedAt == null ? now : joinedAt, null, now);
	}

	public CallParticipant left(Instant now) {
		return new CallParticipant(id, callId, userId, CallParticipantState.LEFT, invitedAt, joinedAt, now, now);
	}

	public CallParticipant decided(CallParticipantState next, Instant now) {
		return new CallParticipant(id, callId, userId, next, invitedAt, joinedAt, now, now);
	}

	public CallParticipant seen(Instant now) {
		return new CallParticipant(id, callId, userId, state, invitedAt, joinedAt, leftAt, now);
	}
}
