package com.socialcoisa.rede.identity.application.port.in;

import com.socialcoisa.rede.identity.application.AuthResponse;
import com.socialcoisa.rede.identity.application.SignupCommand;

public interface SignupUseCase {

	AuthResponse signup(SignupCommand command);
}
