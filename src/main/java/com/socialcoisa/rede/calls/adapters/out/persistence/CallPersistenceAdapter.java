package com.socialcoisa.rede.calls.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.calls.application.port.out.CallRepository;
import com.socialcoisa.rede.calls.domain.CallParticipant;
import com.socialcoisa.rede.calls.domain.CallParticipantState;
import com.socialcoisa.rede.calls.domain.CallSession;
import com.socialcoisa.rede.calls.domain.CallState;

@Repository
class CallPersistenceAdapter implements CallRepository {

	private static final Logger LOG = LoggerFactory.getLogger(CallPersistenceAdapter.class);

	private static final Collection<CallState> OPEN = List.of(CallState.INITIATING, CallState.RINGING,
		CallState.CONNECTING, CallState.ACTIVE);

	private final SpringDataCallSessionJpaRepository calls;
	private final SpringDataCallParticipantJpaRepository participants;

	CallPersistenceAdapter(SpringDataCallSessionJpaRepository calls,
			SpringDataCallParticipantJpaRepository participants) {
		this.calls = calls;
		this.participants = participants;
	}

	@Override
	@Transactional
	public void create(CallSession call, Collection<String> invitedUserIds, Instant now) {
		calls.saveAndFlush(CallSessionJpaEntity.from(call));
		for (String userId : invitedUserIds) {
			save(new CallParticipant("call_participant_" + java.util.UUID.randomUUID().toString().replace("-", ""),
				call.id(), userId, CallParticipantState.INVITED, now, null, null, null));
		}
		participants.flush();
	}

	@Override
	@Transactional
	public boolean persist(CallSession call, CallState from) {
		return calls.advance(call.id(), from, call.state(), call.lastActivityAt(), call.answeredAt(), call.endedAt(),
			call.endedBy(), call.endReason(), call.expiresAt()) == 1;
	}

	@Override
	public Optional<CallSession> findById(String callId) {
		return calls.findById(callId).map(CallSessionJpaEntity::toDomain);
	}

	@Override
	public Optional<CallSession> findOpenByConversation(String conversationId) {
		return calls.findByConversationIdAndStateIn(conversationId, OPEN).stream()
			.min(java.util.Comparator.comparing(CallSessionJpaEntity::createdAt))
			.map(CallSessionJpaEntity::toDomain);
	}

	@Override
	public Optional<CallSession> findOpenByGroup(String groupId) {
		return calls.findByGroupIdAndStateIn(groupId, OPEN).stream()
			.min(java.util.Comparator.comparing(CallSessionJpaEntity::createdAt))
			.map(CallSessionJpaEntity::toDomain);
	}

	@Override
	public List<CallSession> findOpenByParticipant(String userId) {
		return calls.openWithParticipant(userId, OPEN).stream().map(CallSessionJpaEntity::toDomain).toList();
	}

	@Override
	public CallParticipantState participantState(String callId, String userId) {
		return participants.findByCallIdAndUserId(callId, userId).map(CallParticipantJpaEntity::state).orElse(null);
	}

	@Override
	public List<CallParticipantState> participantStates(String callId) {
		return participants.findByCallId(callId).stream().map(CallParticipantJpaEntity::state).toList();
	}

	@Override
	public int countPresentExcluding(String callId, String excludedUserId) {
		if (excludedUserId == null) {
			return (int) participants.countByCallIdAndState(callId, CallParticipantState.JOINED);
		}
		return (int) participants.countByCallIdAndStateAndUserIdNot(callId, CallParticipantState.JOINED,
			excludedUserId);
	}

	@Override
	public int countPresentAmong(String callId, Collection<String> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return 0;
		}
		return (int) participants.countByCallIdAndStateAndUserIdIn(callId, CallParticipantState.JOINED, userIds);
	}

	@Override
	@Transactional
	public boolean join(String callId, String userId, Instant now) {
		return participants.advance(callId, userId, CallParticipantState.INVITED, CallParticipantState.JOINED, now,
			null, now) == 1
			|| participants.advance(callId, userId, CallParticipantState.LEFT, CallParticipantState.JOINED, now,
				null, now) == 1;
	}

	@Override
	@Transactional
	public boolean leave(String callId, String userId, Instant now) {
		return participants.advance(callId, userId, CallParticipantState.JOINED, CallParticipantState.LEFT,
			null, now, now) == 1;
	}

	@Override
	@Transactional
	public boolean decide(String callId, String userId, CallParticipantState next, Instant now) {
		return participants.advance(callId, userId, CallParticipantState.INVITED, next, null, now, now) == 1;
	}

	@Override
	@Transactional
	public void settleInvites(String callId, CallParticipantState next, Instant now) {
		participants.settle(callId, CallParticipantState.INVITED, next, now);
	}

	@Override
	@Transactional
	public void abandon(CallSession call, CallState from, Instant now, String reason) {
		participants.settleAllPresent(call.id(), CallParticipantState.JOINED, CallParticipantState.LEFT, now);
		calls.advance(call.id(), from, call.state(), now, call.answeredAt(), call.endedAt(), call.endedBy(),
			reason, call.expiresAt());
	}

	@Override
	public List<CallSession> findExpired(Instant now, int limit) {
		return calls.expired(OPEN, now, PageRequest.of(0, limit)).stream()
			.map(CallSessionJpaEntity::toDomain)
			.toList();
	}

	@Override
	public void logEntry(String callId, String userId, boolean granted, String reason) {
		// Log operacional sem dado pessoal: id da chamada, quem, permitido/negado e o motivo do código.
		LOG.info("call-entry call={} user={} granted={} reason={}", callId, userId, granted, reason);
	}

	private void save(CallParticipant participant) {
		participants.save(CallParticipantJpaEntity.from(participant));
	}
}
