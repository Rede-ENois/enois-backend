package com.socialcoisa.rede.identity.application.port.in;

public interface RequestPasswordResetUseCase {

	void requestReset(String email);
}
