package com.socialcoisa.rede.desabafos.adapters.out.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.desabafos.application.port.out.DesabafosRepository;
import com.socialcoisa.rede.desabafos.domain.DesabafoComment;
import com.socialcoisa.rede.desabafos.domain.DesabafoPost;
import com.socialcoisa.rede.desabafos.domain.DesabafoStatus;

@Component
@Transactional
class DesabafosPersistenceAdapter implements DesabafosRepository {

	private final SpringDataDesabafoPostJpaRepository posts;
	private final SpringDataDesabafoCommentJpaRepository comments;
	private final SpringDataDesabafoReactionJpaRepository reactions;
	private final Clock clock;

	DesabafosPersistenceAdapter(SpringDataDesabafoPostJpaRepository posts,
			SpringDataDesabafoCommentJpaRepository comments, SpringDataDesabafoReactionJpaRepository reactions,
			Clock clock) {
		this.posts = posts;
		this.comments = comments;
		this.reactions = reactions;
		this.clock = clock;
	}

	@Override
	public DesabafoPost savePost(DesabafoPost post) {
		return posts.save(new DesabafoPostJpaEntity(post)).toDomain();
	}

	@Override
	public Optional<DesabafoPost> findPostById(String id) {
		return posts.findById(id).map(DesabafoPostJpaEntity::toDomain);
	}

	@Override
	public List<DesabafoPost> findFeedBefore(Instant before, int limit) {
		return posts.feedBefore(DesabafoStatus.ACTIVE, before, PageRequest.of(0, limit)).stream()
			.map(DesabafoPostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public DesabafoComment saveComment(DesabafoComment comment) {
		return comments.save(new DesabafoCommentJpaEntity(comment)).toDomain();
	}

	@Override
	public Optional<DesabafoComment> findCommentById(String id) {
		return comments.findById(id).map(DesabafoCommentJpaEntity::toDomain);
	}

	@Override
	public List<DesabafoComment> findActiveCommentsByPost(String postId) {
		return comments.findByPostIdAndStatusOrderByCreatedAtAsc(postId, DesabafoStatus.ACTIVE).stream()
			.map(DesabafoCommentJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Map<String, Map<String, Long>> countReactionsByPostIds(List<String> postIds) {
		if (postIds.isEmpty()) {
			return Map.of();
		}
		Map<String, Map<String, Long>> totals = new HashMap<>();
		for (SpringDataDesabafoReactionJpaRepository.ReactionCountProjection row : reactions
			.countByPostIdIn(postIds)) {
			totals.computeIfAbsent(row.getPostId(), chave -> new HashMap<>()).put(row.getReaction(), row.getTotal());
		}
		return totals;
	}

	@Override
	public Map<String, String> findUserReactions(String userId, Set<String> postIds) {
		if (postIds.isEmpty()) {
			return Map.of();
		}
		Map<String, String> minhas = new HashMap<>();
		for (DesabafoReactionJpaEntity reacao : reactions.findByUserIdAndPostIdIn(userId, postIds)) {
			minhas.put(reacao.postId(), reacao.reaction());
		}
		return minhas;
	}

	@Override
	public void react(String postId, String userId, String reaction) {
		reactions.save(new DesabafoReactionJpaEntity(postId, userId, reaction, clock.instant()));
	}

	@Override
	public void removeReaction(String postId, String userId) {
		reactions.deleteByPostIdAndUserId(postId, userId);
	}

	@Override
	public Map<String, Long> countActiveCommentsByPostIds(List<String> postIds) {
		if (postIds.isEmpty()) {
			return Map.of();
		}
		Map<String, Long> totals = new HashMap<>();
		for (SpringDataDesabafoCommentJpaRepository.CommentCountProjection row : comments.countByPostIdIn(postIds,
			DesabafoStatus.ACTIVE)) {
			totals.put(row.getPostId(), row.getTotal());
		}
		return totals;
	}
}
