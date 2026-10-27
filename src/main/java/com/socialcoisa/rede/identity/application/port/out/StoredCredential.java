package com.socialcoisa.rede.identity.application.port.out;

public record StoredCredential(String userId, String email, String passwordHash, boolean passwordUsable,
		boolean resetNeeded) {

	public StoredCredential(String userId, String email, String passwordHash) {
		this(userId, email, passwordHash, true, false);
	}

	public StoredCredential(String userId, String email, String passwordHash, boolean passwordUsable) {
		this(userId, email, passwordHash, passwordUsable, false);
	}
}
