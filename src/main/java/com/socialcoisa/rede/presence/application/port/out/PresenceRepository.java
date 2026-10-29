package com.socialcoisa.rede.presence.application.port.out;

import java.time.Instant;
import java.util.Optional;

import com.socialcoisa.rede.presence.domain.PresenceStatus;
import com.socialcoisa.rede.presence.domain.UserPresence;

public interface PresenceRepository {

	Optional<UserPresence> findByUserId(String userId);

	void setStatus(String userId, PresenceStatus status, Instant seenAt);

	void touch(String userId, Instant seenAt);
}
