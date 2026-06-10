package com.socialcoisa.rede.identity.application;

import java.time.Instant;

public record AuthResponse(String token, String refreshToken, Instant expiresAt, Instant refreshExpiresAt, AuthUserView user) {
}
