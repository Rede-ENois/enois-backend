package com.socialcoisa.rede.identity.application.port.in;

import com.socialcoisa.rede.identity.application.AuthResponse;

public interface RefreshSessionUseCase {

	AuthResponse refresh(String refreshToken);
}
