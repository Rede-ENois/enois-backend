package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.feed.application.port.out.PostInteractionRepository;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.feed.domain.UserId;

@Repository
class PostInteractionPersistenceAdapter implements PostInteractionRepository {

	private final SpringDataReactionJpaRepository reactionRepository;
	private final SpringDataCommentReactionJpaRepository commentReactionRepository;
	private final SpringDataSavedPostJpaRepository savedPostRepository;
	private final SpringDataPostShareJpaRepository postShareRepository;

	PostInteractionPersistenceAdapter(SpringDataReactionJpaRepository reactionRepository,
			SpringDataCommentReactionJpaRepository commentReactionRepository,
			SpringDataSavedPostJpaRepository savedPostRepository,
			SpringDataPostShareJpaRepository postShareRepository) {
		this.reactionRepository = reactionRepository;
		this.commentReactionRepository = commentReactionRepository;
		this.savedPostRepository = savedPostRepository;
		this.postShareRepository = postShareRepository;
	}

	@Override
	public boolean addReaction(String id, PostId postId, UserId userId, ReactionType type, Instant createdAt) {
		var existing = reactionRepository.findByPostIdAndUserId(postId.value(), userId.value());
		if (existing.isPresent()) {
			var old = existing.get();
			if (old.getReactionType() == type) {
				reactionRepository.delete(old);
				return false;
			}
			reactionRepository.delete(old);
			reactionRepository.flush();
		}
		try {
			reactionRepository.saveAndFlush(new ReactionJpaEntity(id, postId.value(), userId.value(), type, createdAt));
			return true;
		}
		catch (DataIntegrityViolationException exception) {
			return false;
		}
	}

	@Override
	public boolean removeReaction(PostId postId, UserId userId) {
		return reactionRepository.findByPostIdAndUserId(postId.value(), userId.value())
			.map(reaction -> {
				reactionRepository.delete(reaction);
				return true;
			})
			.orElse(false);
	}

	@Override
	public List<String> findSavedPostIdsByUserId(UserId userId) {
		return savedPostRepository.findByUserIdOrderByCreatedAtDesc(userId.value()).stream()
			.map(SavedPostJpaEntity::getPostId)
			.toList();
	}

	@Override
	public Map<PostId, ReactionType> findReactionsByUserAndPostIds(UserId userId, Collection<PostId> postIds) {
		List<String> ids = postIds.stream().map(PostId::value).toList();
		List<ReactionJpaEntity> reactions = reactionRepository.findByPostIdInAndUserId(ids, userId.value());
		Map<PostId, ReactionType> result = new HashMap<>();
		for (ReactionJpaEntity r : reactions) {
			result.put(new PostId(r.getPostId()), r.getReactionType());
		}
		return result;
	}

	@Override
	public List<ReactionSummary> findReactionsByPostId(PostId postId, Instant before, int limit) {
		return reactionRepository.findByPostIdOrderByCreatedAtDesc(postId.value()).stream()
			.map(r -> new ReactionSummary(r.getUserId(), r.getReactionType(), r.getCreatedAt()))
			.toList();
	}

	@Override
	public boolean addSave(String id, PostId postId, UserId userId, Instant createdAt) {
		if (savedPostRepository.existsByPostIdAndUserId(postId.value(), userId.value())) {
			return false;
		}
		try {
			savedPostRepository.saveAndFlush(new SavedPostJpaEntity(id, postId.value(), userId.value(), createdAt));
			return true;
		}
		catch (DataIntegrityViolationException exception) {
			return false;
		}
	}

	@Override
	public boolean removeSave(PostId postId, UserId userId) {
		return savedPostRepository.findByPostIdAndUserId(postId.value(), userId.value())
			.map(savedPost -> {
				savedPostRepository.delete(savedPost);
				return true;
			})
			.orElse(false);
	}

	@Override
	public boolean addShare(String id, PostId postId, UserId userId, Instant createdAt) {
		if (postShareRepository.existsByPostIdAndUserId(postId.value(), userId.value())) {
			return false;
		}
		try {
			postShareRepository.saveAndFlush(new PostShareJpaEntity(id, postId.value(), userId.value(), createdAt));
			return true;
		}
		catch (DataIntegrityViolationException exception) {
			return false;
		}
	}

	@Override
	public boolean addCommentReaction(String id, String commentId, UserId userId, ReactionType type, Instant createdAt) {
		var existing = commentReactionRepository.findByCommentIdAndUserId(commentId, userId.value());
		if (existing.isPresent()) {
			var old = existing.get();
			if (old.getReactionType() == type) {
				commentReactionRepository.delete(old);
				return false;
			}
			commentReactionRepository.delete(old);
			commentReactionRepository.flush();
		}
		try {
			commentReactionRepository.saveAndFlush(new CommentReactionJpaEntity(id, commentId, userId.value(), type, createdAt));
			return true;
		}
		catch (DataIntegrityViolationException exception) {
			return false;
		}
	}

	@Override
	public boolean removeCommentReaction(String commentId, UserId userId) {
		return commentReactionRepository.findByCommentIdAndUserId(commentId, userId.value())
			.map(reaction -> {
				commentReactionRepository.delete(reaction);
				return true;
			})
			.orElse(false);
	}

	@Override
	public List<ReactionSummary> findReactionsByCommentId(String commentId) {
		return commentReactionRepository.findByCommentIdOrderByCreatedAtDesc(commentId).stream()
			.map(r -> new ReactionSummary(r.getUserId(), r.getReactionType(), r.getCreatedAt()))
			.toList();
	}
}
