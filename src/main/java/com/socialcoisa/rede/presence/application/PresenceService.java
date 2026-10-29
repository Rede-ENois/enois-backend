package com.socialcoisa.rede.presence.application;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.presence.application.port.out.PresenceRepository;
import com.socialcoisa.rede.presence.domain.PresenceStatus;
import com.socialcoisa.rede.presence.domain.UserPresence;

@Service
public class PresenceService {
	private static final Duration ONLINE_WINDOW = Duration.ofMinutes(2);

	private final PresenceRepository repository;
	private final Clock clock;

	PresenceService(PresenceRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PresenceStatus ownStatus(String userId) {
		return repository.findByUserId(userId).map(UserPresence::status).orElse(PresenceStatus.AVAILABLE);
	}

	@Transactional
	public PresenceStatus setStatus(String userId, PresenceStatus status) {
		repository.setStatus(userId, status, clock.instant());
		return status;
	}

	@Transactional
	public void heartbeat(String userId) {
		repository.touch(userId, clock.instant());
	}

	/** O modo invisível e sessões sem atividade recente nunca revelam atividade a terceiros. */
	@Transactional(readOnly = true)
	public String publicStatus(String userId) {
		return repository.findByUserId(userId)
			.filter(p -> p.status() != PresenceStatus.INVISIBLE)
			.filter(p -> !p.lastSeenAt().isBefore(clock.instant().minus(ONLINE_WINDOW)))
			.map(p -> p.status().name())
			.orElse("OFFLINE");
	}

	/** Não perturbar silencia o push, mas preserva a notificação dentro do aplicativo. */
	@Transactional(readOnly = true)
	public boolean allowPush(String userId) {
		return ownStatus(userId) != PresenceStatus.DO_NOT_DISTURB;
	}
}
