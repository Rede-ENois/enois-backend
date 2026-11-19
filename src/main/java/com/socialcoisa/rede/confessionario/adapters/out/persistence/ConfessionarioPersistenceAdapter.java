package com.socialcoisa.rede.confessionario.adapters.out.persistence;

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

import com.socialcoisa.rede.confessionario.application.port.out.ConfessionarioRepository;
import com.socialcoisa.rede.confessionario.domain.ConfissaoComment;
import com.socialcoisa.rede.confessionario.domain.ConfissaoPost;
import com.socialcoisa.rede.confessionario.domain.ConfissaoStatus;

@Component
@Transactional
class ConfessionarioPersistenceAdapter implements ConfessionarioRepository {

	private final SpringDataConfissaoPostJpaRepository posts;
	private final SpringDataConfissaoCommentJpaRepository comments;
	private final SpringDataConfissaoReactionJpaRepository reactions;
	private final Clock clock;

	ConfessionarioPersistenceAdapter(SpringDataConfissaoPostJpaRepository posts,
			SpringDataConfissaoCommentJpaRepository comments, SpringDataConfissaoReactionJpaRepository reactions,
			Clock clock) {
		this.posts = posts;
		this.comments = comments;
		this.reactions = reactions;
		this.clock = clock;
	}

	@Override
	public ConfissaoPost savePost(ConfissaoPost post) {
		return posts.save(new ConfissaoPostJpaEntity(post)).toDomain();
	}

	@Override
	public Optional<ConfissaoPost> findPostById(String id) {
		return posts.findById(id).map(ConfissaoPostJpaEntity::toDomain);
	}

	@Override
	public List<ConfissaoPost> findFeedBefore(Instant before, int limit) {
		return posts.feedBefore(ConfissaoStatus.ACTIVE, before, PageRequest.of(0, limit)).stream()
			.map(ConfissaoPostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public ConfissaoComment saveComment(ConfissaoComment comment) {
		return comments.save(new ConfissaoCommentJpaEntity(comment)).toDomain();
	}

	@Override
	public Optional<ConfissaoComment> findCommentById(String id) {
		return comments.findById(id).map(ConfissaoCommentJpaEntity::toDomain);
	}

	@Override
	public List<ConfissaoComment> findActiveCommentsByPost(String postId) {
		return comments.findByPostIdAndStatusOrderByCreatedAtAsc(postId, ConfissaoStatus.ACTIVE).stream()
			.map(ConfissaoCommentJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Map<String, Map<String, Long>> countReactionsByPostIds(List<String> postIds) {
		if (postIds.isEmpty()) {
			return Map.of();
		}
		Map<String, Map<String, Long>> totals = new HashMap<>();
		for (SpringDataConfissaoReactionJpaRepository.ReactionCountProjection row : reactions
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
		for (ConfissaoReactionJpaEntity reacao : reactions.findByUserIdAndPostIdIn(userId, postIds)) {
			minhas.put(reacao.postId(), reacao.reaction());
		}
		return minhas;
	}

	@Override
	public void react(String postId, String userId, String reaction) {
		reactions.save(new ConfissaoReactionJpaEntity(postId, userId, reaction, clock.instant()));
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
		for (SpringDataConfissaoCommentJpaRepository.CommentCountProjection row : comments.countByPostIdIn(postIds,
			ConfissaoStatus.ACTIVE)) {
			totals.put(row.getPostId(), row.getTotal());
		}
		return totals;
	}
}
