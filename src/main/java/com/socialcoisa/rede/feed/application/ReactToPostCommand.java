package com.socialcoisa.rede.feed.application;

import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.feed.domain.UserId;

public record ReactToPostCommand(UserId userId, PostId postId, ReactionType type) {
}
