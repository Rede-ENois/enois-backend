package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.Optional;

import com.socialcoisa.rede.feed.domain.PostId;

public record GetCommentsQuery(PostId postId, Optional<Instant> before, int limit) {
}
