package com.socialcoisa.rede.identity.adapters.out.oauth;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.identity.application.port.out.TokenValidationResult;
import com.socialcoisa.rede.identity.application.port.out.TokenValidator;
import com.socialcoisa.rede.identity.domain.OAuthProvider;

@Component
class SimulatedTokenValidator implements TokenValidator {

	@Override
	public Optional<TokenValidationResult> validate(OAuthProvider provider, String authorizationCode) {
		if (authorizationCode == null || authorizationCode.isBlank()) {
			return Optional.empty();
		}
		String externalUserId = provider.name().toLowerCase() + "_" + authorizationCode.substring(0,
			Math.min(16, authorizationCode.length()));
		String email = "user@" + provider.name().toLowerCase() + ".com";
		String name = "User via " + provider.name();
		return Optional.of(new TokenValidationResult(externalUserId, email, name));
	}
}
