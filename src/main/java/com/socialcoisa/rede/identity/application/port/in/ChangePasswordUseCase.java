package com.socialcoisa.rede.identity.application.port.in;

public interface ChangePasswordUseCase {

	void changePassword(String sessionToken, String currentPassword, String newPassword);
}