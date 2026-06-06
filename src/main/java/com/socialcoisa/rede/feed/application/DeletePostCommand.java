package com.socialcoisa.rede.feed.application;

import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

public record DeletePostCommand(UserId userId, PostId postId, String reason) {
}
