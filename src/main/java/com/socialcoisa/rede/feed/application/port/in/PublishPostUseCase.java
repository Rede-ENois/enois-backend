package com.socialcoisa.rede.feed.application.port.in;

import com.socialcoisa.rede.feed.application.PostView;
import com.socialcoisa.rede.feed.application.PublishPostCommand;

public interface PublishPostUseCase {

	PostView publish(PublishPostCommand command);
}
