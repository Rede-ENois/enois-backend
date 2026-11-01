package com.socialcoisa.rede.feed.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.socialcoisa.rede.feed.domain.PostRepost;

public interface PostRepostRepository {

	PostRepost save(PostRepost repost);

	void delete(String userId, String postId);

	boolean exists(String userId, String postId);
	
	List<String> findPostIdsByUserId(String userId, int limit);

	java.util.Map<String, List<PostRepost>> findRepostsByPostIds(java.util.Collection<String> postIds);

	/** Reposts recentes de varios users em batch (uma query, sem N+1). Chave = post ID. */
	java.util.Map<String, List<PostRepost>> findRepostsByUserIds(java.util.Collection<String> userIds, int limitPerUser);
}
