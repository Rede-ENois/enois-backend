package com.socialcoisa.rede.feed.application.port.in;

import com.socialcoisa.rede.feed.application.PostActionResult;
import com.socialcoisa.rede.feed.application.ReactToPostCommand;

public interface ReactToPostUseCase {

	PostActionResult react(ReactToPostCommand command);

	PostActionResult removeReaction(ReactToPostCommand command);
}
