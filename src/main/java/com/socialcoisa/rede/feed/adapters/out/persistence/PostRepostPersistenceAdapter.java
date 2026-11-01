package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.feed.application.port.out.PostRepostRepository;
import com.socialcoisa.rede.feed.domain.PostRepost;

@Repository
class PostRepostPersistenceAdapter implements PostRepostRepository {

	private final SpringDataPostRepostJpaRepository repository;

	PostRepostPersistenceAdapter(SpringDataPostRepostJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public PostRepost save(PostRepost repost) {
		return repository.save(PostRepostJpaEntity.fromDomain(repost)).toDomain();
	}

	@Override
	public void delete(String userId, String postId) {
		repository.findByUserIdAndPostId(userId, postId).ifPresent(repository::delete);
	}

	@Override
	public boolean exists(String userId, String postId) {
		return repository.findByUserIdAndPostId(userId, postId).isPresent();
	}

	@Override
	public List<String> findPostIdsByUserId(String userId, int limit) {
		return repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, Math.max(1, limit)))
			.stream().map(PostRepostJpaEntity::getPostId).toList();
	}

	@Override
	public Map<String, List<PostRepost>> findRepostsByPostIds(java.util.Collection<String> postIds) {
		Map<String, List<PostRepost>> result = new LinkedHashMap<>();
		if (postIds == null || postIds.isEmpty()) return result;
		for (PostRepostJpaEntity entity : repository.findByPostIdIn(postIds)) {
			result.computeIfAbsent(entity.getPostId(), k -> new java.util.ArrayList<>())
				.add(entity.toDomain());
		}
		return result;
	}

	@Override
	public Map<String, List<PostRepost>> findRepostsByUserIds(java.util.Collection<String> userIds, int limitPerUser) {
		Map<String, List<PostRepost>> result = new LinkedHashMap<>();
		if (userIds == null || userIds.isEmpty()) return result;
		// Uma query batch: todos os reposts desses users, ordenados pelo mais recente.
		for (PostRepostJpaEntity entity : repository.findByUserIdInOrderByCreatedAtDesc(userIds)) {
			result.computeIfAbsent(entity.getPostId(), k -> new java.util.ArrayList<>())
				.add(entity.toDomain());
		}
		return result;
	}

	@Override
	public java.util.Set<String> findPostIdsRepostedByUser(String userId, java.util.Collection<String> postIds) {
		if (userId == null || postIds == null || postIds.isEmpty()) return java.util.Set.of();
		return repository.findByUserIdAndPostIdIn(userId, postIds).stream()
			.map(PostRepostJpaEntity::getPostId)
			.collect(java.util.stream.Collectors.toSet());
	}
}
