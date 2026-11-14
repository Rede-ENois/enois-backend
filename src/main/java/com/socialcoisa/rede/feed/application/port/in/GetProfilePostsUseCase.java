package com.socialcoisa.rede.feed.application.port.in;

import java.time.Instant;
import java.util.Optional;

import com.socialcoisa.rede.feed.application.FeedPage;
import com.socialcoisa.rede.feed.application.GetProfilePostsQuery;
import com.socialcoisa.rede.feed.application.ProfileRepliesPage;
import com.socialcoisa.rede.feed.domain.UserId;

public interface GetProfilePostsUseCase {

	FeedPage getProfilePosts(GetProfilePostsQuery query);

	ProfileRepliesPage getProfileReplies(UserId viewerId, String username, Optional<Instant> before, int limit);
}
