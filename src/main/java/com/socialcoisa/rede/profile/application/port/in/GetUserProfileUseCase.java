package com.socialcoisa.rede.profile.application.port.in;

import com.socialcoisa.rede.profile.application.UserProfileView;

public interface GetUserProfileUseCase {

	UserProfileView getByUsername(String username);

	UserProfileView getById(String userId);
}
