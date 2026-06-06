package com.socialcoisa.rede.identity.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.identity.domain.OAuthProvider;

public interface TokenValidator {

	Optional<TokenValidationResult> validate(OAuthProvider provider, String authorizationCode);
}
