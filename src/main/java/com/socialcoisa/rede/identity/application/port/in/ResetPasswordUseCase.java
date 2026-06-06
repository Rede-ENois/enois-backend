package com.socialcoisa.rede.identity.application.port.in;

public interface ResetPasswordUseCase {

	void reset(String token, String newPassword);
}
