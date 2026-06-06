package com.socialcoisa.rede.identity.application.port.out;

import java.util.Optional;

public interface CredentialRepository {

	void save(String userId, String email, String passwordHash);

	Optional<StoredCredential> findByEmail(String email);

	Optional<StoredCredential> findByUserId(String userId);

	boolean existsByEmail(String email);
}
