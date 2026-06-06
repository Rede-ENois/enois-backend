package com.socialcoisa.rede.feed.application.port.out;

import com.socialcoisa.rede.feed.domain.PostId;

public interface IdGenerator {

	PostId newPostId();
}
