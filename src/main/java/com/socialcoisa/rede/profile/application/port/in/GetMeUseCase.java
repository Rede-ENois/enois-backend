package com.socialcoisa.rede.profile.application.port.in;

import com.socialcoisa.rede.profile.application.OwnProfileView;

public interface GetMeUseCase {

	OwnProfileView getMe(String userId);
}
