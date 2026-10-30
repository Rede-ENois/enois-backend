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
	public ReactionOutcome addReaction(String id, PostId postId, UserId userId, ReactionType type, Instant createdAt) {
		var existing = reactionRepository.findByPostIdAndUserId(postId.value(), userId.value());
		boolean replacing = false;
		if (existing.isPresent()) {
			var old = existing.get();
			if (old.getReactionType() == type) {
				// Toggle: a mesma reacao foi tocada de novo -> desfaz.
				reactionRepository.delete(old);
				reactionRepository.flush();
				return ReactionOutcome.REMOVED;
			}
			reactionRepository.delete(old);
			reactionRepository.flush();
			replacing = true;
		}
		try {
			reactionRepository.saveAndFlush(new ReactionJpaEntity(id, postId.value(), userId.value(), type, createdAt));
			return replacing ? ReactionOutcome.REPLACED : ReactionOutcome.ADDED;
		}
		catch (DataIntegrityViolationException exception) {
			// Corrida: outra requisicao ja gravou a reacao deste usuario; o total ja a contempla.
			// Tratar como troca evita incrementar de novo.
			return ReactionOutcome.REPLACED;
		}
	}

	@Override
	public long countReactionsReceived(String authorId) {
		return reactionRepository.countReceivedExcludingSelf(authorId);
	}

	@Override
	public long countReactionsReceivedSince(String authorId, java.time.Instant since) {
		return reactionRepository.countReceivedSince(authorId, since);
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
	public List<String> findReactedPostIdsByUserId(UserId userId, int limit) {
		return reactionRepository.findPostIdsByUserId(userId.value(),
			org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)));
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
		// Bounded in SQL: a viral post must not load every reaction into memory to draw a list.
		Instant until = before != null ? before : Instant.parse("9999-12-31T23:59:59Z");
		return reactionRepository.findByPostIdAndCreatedAtBeforeOrderByCreatedAtDesc(postId.value(), until,
				org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit))).stream()
			.map(r -> new ReactionSummary(r.getUserId(), r.getReactionType(), r.getCreatedAt()))
			.toList();
	}

	@Override
	public Map<PostId, Map<ReactionType, Long>> countReactionsByPostIds(Collection<PostId> postIds,
			Collection<String> excludedUserIds) {
		if (postIds.isEmpty()) {
			return Map.of();
		}
		List<String> ids = postIds.stream().map(PostId::value).distinct().toList();
		// Placeholder keeps NOT IN valid when nobody is excluded (same trick as the topic filters).
		Collection<String> excluded = excludedUserIds == null || excludedUserIds.isEmpty() ? List.of("")
			: excludedUserIds;
		Map<PostId, Map<ReactionType, Long>> result = new HashMap<>();
		for (Object[] row : reactionRepository.countByPostIdsGroupedByType(ids, excluded)) {
			result.computeIfAbsent(new PostId((String) row[0]), key -> new java.util.EnumMap<>(ReactionType.class))
				.put((ReactionType) row[1], (Long) row[2]);
		}
		return result;
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
	public Map<String, ReactionType> findCommentReactionsByUser(UserId userId, Collection<String> commentIds) {
		if (commentIds == null || commentIds.isEmpty()) {
			return Map.of();
		}
		Map<String, ReactionType> result = new HashMap<>();
		for (CommentReactionJpaEntity r : commentReactionRepository.findByCommentIdInAndUserId(commentIds, userId.value())) {
			result.put(r.getCommentId(), r.getReactionType());
		}
		return result;
	}

	@Override
	public List<ReactionSummary> findReactionsByCommentId(String commentId) {
		return commentReactionRepository.findByCommentIdOrderByCreatedAtDesc(commentId).stream()
			.map(r -> new ReactionSummary(r.getUserId(), r.getReactionType(), r.getCreatedAt()))
			.toList();
	}

	@Override
	public Map<String, Map<ReactionType, Long>> countCommentReactionsByType(Collection<String> commentIds,
			Collection<String> excludedUserIds) {
		if (commentIds == null || commentIds.isEmpty()) return Map.of();
		Collection<String> excluded = excludedUserIds == null ? java.util.List.of() : excludedUserIds;
		Map<String, Map<ReactionType, Long>> counts = new HashMap<>();
		for (CommentReactionJpaEntity r : commentReactionRepository.findByCommentIdIn(commentIds)) {
			if (excluded.contains(r.getUserId())) continue;
			counts.computeIfAbsent(r.getCommentId(), key -> new HashMap<>())
				.merge(r.getReactionType(), 1L, Long::sum);
		}
		return counts;
	}
}
