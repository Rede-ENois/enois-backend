package com.socialcoisa.rede.media.domain;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

public record MediaDeletionJob(String id, String mediaId, PostId postId, UserId ownerId, String status, int attempts,
		Instant createdAt, Instant nextAttemptAt) {
}
