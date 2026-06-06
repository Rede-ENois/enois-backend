package com.socialcoisa.rede.identity.application;

import java.time.Instant;

public record AuthResponse(String token, Instant expiresAt, AuthUserView user) {
}
