package com.socialcoisa.rede.feed.application.port.in;

import com.socialcoisa.rede.feed.application.CommentOnPostCommand;
import com.socialcoisa.rede.feed.application.CommentView;

public interface CommentOnPostUseCase {

	CommentView comment(CommentOnPostCommand command);
}
