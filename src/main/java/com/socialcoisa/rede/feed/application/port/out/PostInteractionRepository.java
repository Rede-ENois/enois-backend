package com.socialcoisa.rede.feed.application.port.out;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.feed.domain.UserId;

public interface PostInteractionRepository {

	boolean addReaction(String id, PostId postId, UserId userId, ReactionType type, Instant createdAt);

	boolean removeReaction(PostId postId, UserId userId);

	boolean addSave(String id, PostId postId, UserId userId, Instant createdAt);

	boolean removeSave(PostId postId, UserId userId);

	boolean addShare(String id, PostId postId, UserId userId, Instant createdAt);
}
