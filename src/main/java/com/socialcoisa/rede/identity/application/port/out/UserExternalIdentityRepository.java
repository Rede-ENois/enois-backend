package com.socialcoisa.rede.identity.application.port.out;

import java.util.Optional;

public interface UserExternalIdentityRepository {

	void save(String userId, String provider, String externalUserId, String email);

	Optional<String> findUserIdByProviderAndExternalId(String provider, String externalUserId);

	boolean existsByProviderAndExternalId(String provider, String externalUserId);
}
