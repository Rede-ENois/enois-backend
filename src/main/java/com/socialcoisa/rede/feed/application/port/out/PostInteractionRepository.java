package com.socialcoisa.rede.feed.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.feed.domain.UserId;

public interface PostInteractionRepository {

	boolean addReaction(String id, PostId postId, UserId userId, ReactionType type, Instant createdAt);

	boolean removeReaction(PostId postId, UserId userId);

	boolean addSave(String id, PostId postId, UserId userId, Instant createdAt);

	boolean removeSave(PostId postId, UserId userId);

	boolean addShare(String id, PostId postId, UserId userId, Instant createdAt);

	List<String> findSavedPostIdsByUserId(UserId userId);

	Map<PostId, ReactionType> findReactionsByUserAndPostIds(UserId userId, Collection<PostId> postIds);

	List<ReactionSummary> findReactionsByPostId(PostId postId, Instant before, int limit);

	boolean addCommentReaction(String id, String commentId, UserId userId, ReactionType type, Instant createdAt);
	boolean removeCommentReaction(String commentId, UserId userId);
	List<ReactionSummary> findReactionsByCommentId(String commentId);

	record ReactionSummary(String userId, ReactionType type, Instant createdAt) {
	}
}
