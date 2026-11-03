package com.socialcoisa.rede.presence.domain;

import java.time.Instant;

public record UserPresence(PresenceStatus status, Instant lastSeenAt) {
}
