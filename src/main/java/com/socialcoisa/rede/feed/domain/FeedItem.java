package com.socialcoisa.rede.feed.domain;

import java.time.Instant;

public record FeedItem(PostId postId, UserId authorId, double score, Instant createdAt, FeedReason reason) {
}
