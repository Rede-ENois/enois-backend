package com.socialcoisa.rede.media.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.out.MediaDeletionJobRepository;
import com.socialcoisa.rede.media.domain.MediaDeletionJob;

@Repository
class MediaDeletionJobPersistenceAdapter implements MediaDeletionJobRepository {

	private final SpringDataMediaDeletionJobJpaRepository repository;

	MediaDeletionJobPersistenceAdapter(SpringDataMediaDeletionJobJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public void request(String id, String mediaId, PostId postId, UserId ownerId, Instant createdAt) {
		if (repository.findByMediaId(mediaId).isPresent()) {
			return;
		}
		repository.save(new MediaDeletionJobJpaEntity(id, mediaId, postId == null ? null : postId.value(),
			ownerId.value(), createdAt));
	}

	@Override
	public Optional<String> findStatusByMediaId(String mediaId) {
		return repository.findByMediaId(mediaId).map(MediaDeletionJobJpaEntity::status);
	}

	@Override
	public List<MediaDeletionJob> findPending(Instant now, int limit) {
		return repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("REQUESTED", now,
			PageRequest.of(0, limit)).stream().map(MediaDeletionJobJpaEntity::toDomain).toList();
	}

	@Override
	public void complete(String mediaId, Instant processedAt) {
		repository.findByMediaId(mediaId).ifPresent(job -> {
			job.complete(processedAt);
			repository.save(job);
		});
	}

	@Override
	public void fail(String mediaId, String errorMessage, Instant nextAttemptAt) {
		repository.findByMediaId(mediaId).ifPresent(job -> {
			job.fail(errorMessage, nextAttemptAt);
			repository.save(job);
		});
	}
}
