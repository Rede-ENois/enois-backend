package com.socialcoisa.rede.identity.application.port.in;

import com.socialcoisa.rede.identity.domain.OAuthProvider;

public record OAuthStartResponse(OAuthProvider provider, String state, String authorizationUrl) {
}
