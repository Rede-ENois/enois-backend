package com.socialcoisa.rede.feed.application.port.in;

import com.socialcoisa.rede.feed.application.FeedPage;
import com.socialcoisa.rede.feed.application.GetProfilePostsQuery;

public interface GetProfilePostsUseCase {

	FeedPage getProfilePosts(GetProfilePostsQuery query);
}
