package com.socialcoisa.rede.media.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.media.application.port.out.MediaRenderJobRepository;
import com.socialcoisa.rede.media.domain.MediaRenderJob;
import com.socialcoisa.rede.media.domain.MediaRenderJobStatus;

@Component
class MediaRenderJobPersistenceAdapter implements MediaRenderJobRepository {

	private final SpringDataMediaRenderJobJpaRepository repository;

	MediaRenderJobPersistenceAdapter(SpringDataMediaRenderJobJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public Optional<MediaRenderJob> find(String mediaId) {
		return mediaId == null ? Optional.empty() : repository.findById(mediaId).map(MediaRenderJobJpaEntity::toDomain);
	}

	@Override
	public MediaRenderJob ensurePending(MediaRenderJob job) {
		Optional<MediaRenderJob> existing = find(job.mediaId());
		if (existing.isPresent()) {
			return existing.get();
		}
		try {
			return repository.save(MediaRenderJobJpaEntity.fromDomain(job)).toDomain();
		}
		catch (DataIntegrityViolationException corrida) {
			// Duas subidas do mesmo documento ao mesmo tempo: quem chegou depois lê o pedido do outro,
			// em vez de falhar a subida por causa da chave primária.
			return find(job.mediaId()).orElseThrow(() -> corrida);
		}
	}

	@Override
	public MediaRenderJob save(MediaRenderJob job) {
		return repository.save(MediaRenderJobJpaEntity.fromDomain(job)).toDomain();
	}

	@Override
	public List<MediaRenderJob> findPending(int limit, Instant updatedBefore) {
		return repository
			.findByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(MediaRenderJobStatus.PENDING, updatedBefore,
				PageRequest.of(0, Math.max(1, limit)))
			.stream()
			.map(MediaRenderJobJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Optional<MediaRenderJob> documentOfPageKey(String objectKey) {
		if (objectKey == null || objectKey.isBlank()) {
			return Optional.empty();
		}
		return repository.findByPageKeysContaining(objectKey, PageRequest.of(0, 1)).stream()
			.findFirst()
			.map(MediaRenderJobJpaEntity::toDomain);
	}

	@Override
	public List<MediaRenderJob> findWithPages(int limit) {
		return repository.findByStatusInAndPageKeysIsNotNullAndPageKeysNot(
			List.of(MediaRenderJobStatus.RENDERED, MediaRenderJobStatus.PENDING), "",
			PageRequest.of(0, Math.max(1, limit)))
			.stream()
			.map(MediaRenderJobJpaEntity::toDomain)
			.toList();
	}
}
