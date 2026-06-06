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

	CommentPersistenceAdapter(SpringDataCommentJpaRepository repository) {
		this.repository = repository;
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
	public List<Comment> findActiveByPost(PostId postId, Instant before, int limit) {
		return repository.findByPostIdAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(postId.value(),
			CommentStatus.ACTIVE, before, PageRequest.of(0, limit)).stream()
			.map(CommentJpaEntity::toDomain)
			.toList();
	}
}
