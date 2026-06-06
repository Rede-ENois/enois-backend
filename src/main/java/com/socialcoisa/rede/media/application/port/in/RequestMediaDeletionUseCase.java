package com.socialcoisa.rede.media.application.port.in;

import com.socialcoisa.rede.media.application.RequestMediaDeletionCommand;
import com.socialcoisa.rede.shared.web.StatusResponse;

public interface RequestMediaDeletionUseCase {

	StatusResponse requestDeletion(RequestMediaDeletionCommand command);
}
