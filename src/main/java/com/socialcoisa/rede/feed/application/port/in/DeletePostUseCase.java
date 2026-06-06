package com.socialcoisa.rede.feed.application.port.in;

import com.socialcoisa.rede.feed.application.DeletePostCommand;
import com.socialcoisa.rede.feed.application.DeletePostResult;

public interface DeletePostUseCase {

	DeletePostResult delete(DeletePostCommand command);
}
