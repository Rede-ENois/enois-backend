package com.socialcoisa.rede.identity.application.port.in;

public interface ChangeEmailUseCase {

	void changeEmail(String sessionToken, String newEmail, String password);
}