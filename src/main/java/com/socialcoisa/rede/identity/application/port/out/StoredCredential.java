package com.socialcoisa.rede.identity.application.port.out;

public record StoredCredential(String userId, String email, String passwordHash) {
}
