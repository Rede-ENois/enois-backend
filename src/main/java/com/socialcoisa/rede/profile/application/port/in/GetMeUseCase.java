package com.socialcoisa.rede.profile.application.port.in;

import com.socialcoisa.rede.profile.application.UserProfileView;

public interface GetMeUseCase {

	UserProfileView getMe(String userId);
}
