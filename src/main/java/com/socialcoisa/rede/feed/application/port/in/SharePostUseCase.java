package com.socialcoisa.rede.feed.application.port.in;

import com.socialcoisa.rede.feed.application.PostActionCommand;
import com.socialcoisa.rede.feed.application.PostActionResult;

public interface SharePostUseCase {

	PostActionResult share(PostActionCommand command);
}
