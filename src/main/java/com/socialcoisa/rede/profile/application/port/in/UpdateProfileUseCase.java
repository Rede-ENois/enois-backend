package com.socialcoisa.rede.profile.application.port.in;

import com.socialcoisa.rede.profile.application.UpdateProfileCommand;
import com.socialcoisa.rede.profile.application.UserProfileView;

public interface UpdateProfileUseCase {

	UserProfileView update(UpdateProfileCommand command);
}
