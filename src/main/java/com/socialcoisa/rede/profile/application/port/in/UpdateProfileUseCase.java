package com.socialcoisa.rede.profile.application.port.in;

import com.socialcoisa.rede.profile.application.OwnProfileView;
import com.socialcoisa.rede.profile.application.UpdateProfileCommand;

public interface UpdateProfileUseCase {

	OwnProfileView update(UpdateProfileCommand command);
}
