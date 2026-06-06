package com.socialcoisa.rede.identity.application.port.in;

import com.socialcoisa.rede.identity.application.AuthResponse;
import com.socialcoisa.rede.identity.domain.OAuthProvider;

public interface OAuthUseCase {

	OAuthStartResponse start(OAuthProvider provider);

	AuthResponse callback(OAuthProvider provider, String code, String state);
}
