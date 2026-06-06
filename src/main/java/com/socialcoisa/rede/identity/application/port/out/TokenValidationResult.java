package com.socialcoisa.rede.identity.application.port.out;

import java.util.Optional;

public record TokenValidationResult(String externalUserId, String email, String name) {

	public TokenValidationResult {
		if (externalUserId == null || externalUserId.isBlank()) {
			throw new IllegalArgumentException("externalUserId is required");
		}
	}

	public String email() {
		return email == null ? "" : email;
	}

	public String name() {
		return name == null ? "" : name;
	}
}
