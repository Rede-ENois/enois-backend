package com.socialcoisa.rede.colagem.adapters.out.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.colagem.application.port.out.ColagemRepository;
import com.socialcoisa.rede.colagem.domain.ColagemComment;
import com.socialcoisa.rede.colagem.domain.ColagemPost;
import com.socialcoisa.rede.colagem.domain.ColagemStatus;

@Component
@Transactional
class ColagemPersistenceAdapter implements ColagemRepository {

	private final SpringDataColagemPostJpaRepository posts;
	private final SpringDataColagemCommentJpaRepository comments;
	private final SpringDataColagemReactionJpaRepository reactions;
	private final Clock clock;

	ColagemPersistenceAdapter(SpringDataColagemPostJpaRepository posts,
			SpringDataColagemCommentJpaRepository comments, SpringDataColagemReactionJpaRepository reactions,
			Clock clock) {
		this.posts = posts;
		this.comments = comments;
		this.reactions = reactions;
		this.clock = clock;
	}

	@Override
	public ColagemPost savePost(ColagemPost post) {
		return posts.save(new ColagemPostJpaEntity(post)).toDomain();
	}

	@Override
	public Optional<ColagemPost> findPostById(String id) {
		return posts.findById(id).map(ColagemPostJpaEntity::toDomain);
	}

	@Override
	public List<ColagemPost> findFeedBefore(Instant before, int limit) {
		return posts.feedBefore(ColagemStatus.ACTIVE, before, PageRequest.of(0, limit)).stream()
			.map(ColagemPostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<ColagemPost> findActivePostsByMediaId(String mediaId) {
		return posts.findByMediaIdAndStatus(mediaId, ColagemStatus.ACTIVE).stream()
			.map(ColagemPostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public ColagemComment saveComment(ColagemComment comment) {
		return comments.save(new ColagemCommentJpaEntity(comment)).toDomain();
	}

	@Override
	public Optional<ColagemComment> findCommentById(String id) {
		return comments.findById(id).map(ColagemCommentJpaEntity::toDomain);
	}

	@Override
	public List<ColagemComment> findActiveCommentsByPost(String postId) {
		return comments.findByPostIdAndStatusOrderByCreatedAtAsc(postId, ColagemStatus.ACTIVE).stream()
			.map(ColagemCommentJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<ColagemComment> findActiveCommentsByMediaId(String mediaId) {
		return comments.findByMediaIdAndStatus(mediaId, ColagemStatus.ACTIVE).stream()
			.map(ColagemCommentJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Map<String, Long> countActiveCommentsByPostIds(Collection<String> postIds) {
		if (postIds.isEmpty()) {
			return Map.of();
		}
		Map<String, Long> totals = new HashMap<>();
		for (SpringDataColagemCommentJpaRepository.CommentCountProjection row : comments.countByPostIdIn(postIds,
			ColagemStatus.ACTIVE)) {
			totals.put(row.getPostId(), row.getTotal());
		}
		return totals;
	}

	@Override
	public Map<String, Map<String, Long>> countReactionsByPostIds(Collection<String> postIds) {
		if (postIds.isEmpty()) {
			return Map.of();
		}
		Map<String, Map<String, Long>> totals = new HashMap<>();
		for (SpringDataColagemReactionJpaRepository.ReactionCountProjection row : reactions.countByPostIdIn(postIds)) {
			totals.computeIfAbsent(row.getPostId(), chave -> new HashMap<>()).put(row.getReaction(), row.getTotal());
		}
		return totals;
	}

	@Override
	public Map<String, String> findUserReactions(String userId, Collection<String> postIds) {
		if (postIds.isEmpty()) {
			return Map.of();
		}
		Map<String, String> minhas = new HashMap<>();
		for (ColagemReactionJpaEntity reacao : reactions.findByUserIdAndPostIdIn(userId, postIds)) {
			minhas.put(reacao.postId(), reacao.reaction());
		}
		return minhas;
	}

	@Override
	public void react(String postId, String userId, String reaction) {
		reactions.save(new ColagemReactionJpaEntity(postId, userId, reaction, clock.instant()));
	}

	@Override
	public void removeReaction(String postId, String userId) {
		reactions.deleteByPostIdAndUserId(postId, userId);
	}
}
