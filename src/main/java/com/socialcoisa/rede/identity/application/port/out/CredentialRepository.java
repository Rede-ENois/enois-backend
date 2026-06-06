package com.socialcoisa.rede.identity.application.port.out;

import java.util.Optional;

public interface CredentialRepository {

	void save(String userId, String email, String passwordHash);

	Optional<StoredCredential> findByEmail(String email);

	boolean existsByEmail(String email);
}
