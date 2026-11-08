package com.socialcoisa.rede.presence.application;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.presence.application.port.out.PresenceRepository;
import com.socialcoisa.rede.presence.domain.PresenceStatus;
import com.socialcoisa.rede.presence.domain.UserPresence;

@Service
public class PresenceService {
	private static final Duration ONLINE_WINDOW = Duration.ofMinutes(2);

	private final PresenceRepository repository;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	PresenceService(PresenceRepository repository, ApplicationEventPublisher events, Clock clock) {
		this.repository = repository;
		this.events = events;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PresenceStatus ownStatus(String userId) {
		return repository.findByUserId(userId).map(UserPresence::status).orElse(PresenceStatus.AVAILABLE);
	}

	@Transactional
	public PresenceStatus setStatus(String userId, PresenceStatus status) {
		repository.setStatus(userId, status, clock.instant());
		// Quem depende da presença (o toque da chamada direta) escuta depois do commit.
		events.publishEvent(new PresenceChanged(userId, status));
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

	/**
	 * Chamada direta depende de a pessoa aceitar ser incomodada na hora: "não perturbar" e
	 * "invisível" não recebem toque. A chamada em si (como notificação interna) continua possível
	 * por outros caminhos; este portão vale só para o toque do 1:1.
	 */
	@Transactional(readOnly = true)
	public boolean acceptsDirectCall(String userId) {
		PresenceStatus status = ownStatus(userId);
		return status != PresenceStatus.DO_NOT_DISTURB && status != PresenceStatus.INVISIBLE;
	}
}
