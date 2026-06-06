package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.Optional;

import com.socialcoisa.rede.feed.domain.UserId;

public record GetProfilePostsQuery(UserId viewerId, String username, Optional<Instant> before, int limit) {
}
