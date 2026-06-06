package com.socialcoisa.rede.identity.application.port.in;

import com.socialcoisa.rede.identity.application.AuthResponse;
import com.socialcoisa.rede.identity.application.LoginCommand;

public interface LoginUseCase {

	AuthResponse login(LoginCommand command);
}
