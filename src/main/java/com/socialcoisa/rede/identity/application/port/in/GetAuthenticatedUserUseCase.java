package com.socialcoisa.rede.identity.application.port.in;

import com.socialcoisa.rede.identity.application.AuthUserView;

public interface GetAuthenticatedUserUseCase {

	AuthUserView getAuthenticatedUser(String token);
}
