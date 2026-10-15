package com.socialcoisa.rede.identity.application.port.in;

public interface DeleteAccountUseCase {

	void deleteAccount(String sessionToken, String password);
}