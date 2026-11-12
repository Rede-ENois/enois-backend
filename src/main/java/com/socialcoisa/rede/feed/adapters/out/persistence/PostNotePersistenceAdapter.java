package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.feed.application.port.out.PostNoteRepository;
import com.socialcoisa.rede.feed.domain.PostNote;
import com.socialcoisa.rede.feed.domain.PostNoteStatus;

@Repository
class PostNotePersistenceAdapter implements PostNoteRepository {

	private final SpringDataPostNoteJpaRepository repository;

	PostNotePersistenceAdapter(SpringDataPostNoteJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public PostNote save(PostNote note) {
		return repository.save(PostNoteJpaEntity.fromDomain(note)).toDomain();
	}

	@Override
	public Optional<PostNote> findById(String id) {
		return repository.findById(id).map(PostNoteJpaEntity::toDomain);
	}

	@Override
	public List<PostNote> findApprovedByPostIds(Collection<String> postIds) {
		if (postIds == null || postIds.isEmpty()) {
			return List.of();
		}
		return repository.findByPostIdInAndStatusOrderByCreatedAtDesc(postIds, PostNoteStatus.APPROVED).stream()
			.map(PostNoteJpaEntity::toDomain).toList();
	}

	@Override
	public Optional<PostNote> findByAuthorAndPost(String authorId, String postId) {
		return repository.findFirstByAuthorIdAndPostIdOrderByCreatedAtDesc(authorId, postId)
			.map(PostNoteJpaEntity::toDomain);
	}

	@Override
	public List<PostNote> findByAuthorAndPosts(String authorId, Collection<String> postIds) {
		if (postIds == null || postIds.isEmpty()) {
			return List.of();
		}
		return repository.findByAuthorIdAndPostIdIn(authorId, postIds).stream()
			.map(PostNoteJpaEntity::toDomain).toList();
	}

	@Override
	public List<PostNote> findPendingByPost(String postId) {
		return repository.findByPostIdAndStatusOrderByCreatedAtAsc(postId, PostNoteStatus.PENDING).stream()
			.map(PostNoteJpaEntity::toDomain).toList();
	}

	@Override
	public boolean existsByPostIdAndStatus(String postId, PostNoteStatus status) {
		return repository.existsByPostIdAndStatus(postId, status);
	}

	@Override
	public List<PostNote> findByStatus(PostNoteStatus status, int limit) {
		return repository.findByStatusOrderByCreatedAtAsc(status, PageRequest.of(0, Math.max(1, limit))).stream()
			.map(PostNoteJpaEntity::toDomain).toList();
	}

	@Override
	public int countByAuthorAndStatus(String authorId, PostNoteStatus status) {
		return repository.countByAuthorIdAndStatus(authorId, status);
	}

	@Override
	public Map<String, Integer> countByAuthorsAndStatus(Collection<String> authorIds, PostNoteStatus status) {
		if (authorIds.isEmpty()) {
			return Map.of();
		}
		return repository.countByAuthorIdInAndStatus(authorIds, status).stream()
			.collect(java.util.stream.Collectors.toMap(
				row -> (String) row[0], row -> ((Number) row[1]).intValue()));
	}
}
