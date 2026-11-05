package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.CommentStatus;
import com.socialcoisa.rede.feed.domain.PostId;

@Repository
class CommentPersistenceAdapter implements CommentRepository {

	private final SpringDataCommentJpaRepository repository;
	private final SpringDataPostJpaRepository posts;

	CommentPersistenceAdapter(SpringDataCommentJpaRepository repository, SpringDataPostJpaRepository posts) {
		this.repository = repository;
		this.posts = posts;
	}

	@Override
	public Comment save(Comment comment) {
		return repository.save(CommentJpaEntity.fromDomain(comment)).toDomain();
	}

	@Override
	public Optional<Comment> findById(String id) {
		return repository.findById(id).map(CommentJpaEntity::toDomain);
	}

	@Override
	public void deleteById(String id) {
		// Every comment deletion (author, ban cleanup) routes here, so a deleted accepted answer
		// can never leave its question "resolved" pointing at nothing. Same transaction as the delete.
		posts.clearAcceptedAnswers(List.of(id));
		repository.deleteById(id);
	}

	@Override
	public void markDeleted(Comment comment) {
		// Same invariant as deleteById: a removed accepted answer cannot keep its question resolved.
		posts.clearAcceptedAnswers(List.of(comment.id()));
		repository.save(CommentJpaEntity.fromDomain(comment.deleted()));
	}

	@Override
	public List<String> findPostIdsByAuthor(String authorId) {
		return repository.findByAuthorId(authorId).stream()
			.map(CommentJpaEntity::getPostId)
			.distinct()
			.toList();
	}

	@Override
	public List<Comment> findAllByAuthor(String authorId) {
		return repository.findByAuthorId(authorId).stream()
			.map(CommentJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Comment> findActiveByPost(PostId postId, Instant before, int limit) {
		return repository.findByPostIdAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(postId.value(),
			CommentStatus.ACTIVE, before, PageRequest.of(0, limit)).stream()
			.map(CommentJpaEntity::toDomain)
			.toList();
	}


	@Override
	public Optional<Comment> findPinnedByPost(PostId postId) {
		return repository.findFirstByPostIdAndStatusAndPinnedTrueOrderByCreatedAtDesc(postId.value(),
			CommentStatus.ACTIVE).map(CommentJpaEntity::toDomain);
	}

	@Override
	public List<Comment> findActiveByPostExcludingPinned(PostId postId, Instant before, int limit) {
		return repository.findByPostIdAndStatusAndPinnedFalseAndCreatedAtBeforeOrderByCreatedAtDesc(postId.value(),
			CommentStatus.ACTIVE, before, PageRequest.of(0, limit)).stream()
			.map(CommentJpaEntity::toDomain)
			.toList();
	}

	@Override
	public void unpinAllByPost(PostId postId) {
		repository.unpinAll(postId.value());
	}

	@Override
	public List<Comment> findAllByPostIds(java.util.Collection<String> postIds) {
		if (postIds == null || postIds.isEmpty()) {
			return List.of();
		}
		return repository.findByPostIdIn(postIds).stream().map(CommentJpaEntity::toDomain).toList();
	}
}
