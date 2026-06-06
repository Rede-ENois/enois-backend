package com.socialcoisa.rede.feed.application;

import java.util.List;

import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

public record BulkDeletePostsCommand(UserId userId, List<PostId> postIds, String reason) {
}
