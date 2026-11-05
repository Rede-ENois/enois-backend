package com.socialcoisa.rede.media.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaStorageProvider;

@Repository
class MediaAssetPersistenceAdapter implements MediaAssetRepository {

	private final SpringDataMediaAssetJpaRepository repository;

	MediaAssetPersistenceAdapter(SpringDataMediaAssetJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public MediaAsset save(MediaAsset asset) {
		return repository.save(MediaAssetJpaEntity.fromDomain(asset)).toDomain();
	}

	@Override
	public Optional<MediaAsset> findById(String id) {
		return repository.findById(id).map(MediaAssetJpaEntity::toDomain);
	}

	@Override
	public List<MediaAsset> findByIds(Collection<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		return repository.findByIdIn(ids).stream().map(MediaAssetJpaEntity::toDomain).toList();
	}

	@Override
	public boolean hasReportCapture(Collection<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return false;
		}
		return repository.existsByIdInAndPurpose(ids,
			com.socialcoisa.rede.media.application.MediaService.REPORT_CAPTURE_PURPOSE);
	}

	@Override
	public List<MediaAsset> findForS3Copy(int limit, java.time.Instant before, int maxAttempts) {
		return repository
			.findForS3Copy(MediaStorageProvider.IMAGEKIT, com.socialcoisa.rede.media.domain.MediaDeleteStatus.NONE,
				before, maxAttempts, before, PageRequest.of(0, Math.max(1, limit)))
			.stream()
			.map(MediaAssetJpaEntity::toDomain)
			.toList();
	}

	@Override
	@org.springframework.transaction.annotation.Transactional
	public boolean claimForS3Copy(String id, java.time.Instant now, java.time.Instant staleBefore, int maxAttempts) {
		return repository.claimForS3Copy(id, now, staleBefore, maxAttempts) > 0;
	}

	@Override
	public List<MediaAsset> findForS3Flip(int limit) {
		return repository.findPendingFlip(MediaStorageProvider.S3,
				com.socialcoisa.rede.media.domain.MediaDeleteStatus.NONE, PageRequest.of(0, Math.max(1, limit)))
			.stream()
			.map(MediaAssetJpaEntity::toDomain)
			.toList();
	}

	@Override
	public long countByStorageProvider(MediaStorageProvider provider) {
		return repository.countByStorageProvider(provider);
	}

	@Override
	public long countMigratedToS3() {
		return repository.countByS3MigratedAtIsNotNull();
	}

	@Override
	public long countWithMigrationError() {
		return repository.countByS3LastErrorIsNotNull();
	}

	@Override
	public Optional<String> lastMigrationError() {
		return repository.findFirstByS3LastErrorIsNotNullOrderByCreatedAtDesc()
			.map(entity -> entity.toDomain().s3LastError());
	}

	@Override
	public long countActiveOnS3() {
		return repository.countActiveOnProvider(MediaStorageProvider.S3);
	}

	@Override
	public long countAll() {
		return repository.count();
	}

	@Override
	@org.springframework.transaction.annotation.Transactional
	public void recordDimensions(String id, int width, int height) {
		repository.recordDimensions(id, width, height);
	}

	@Override
	@org.springframework.transaction.annotation.Transactional
	public void recordDimensionFailure(String id) {
		repository.recordDimensionFailure(id);
	}

	@Override
	public List<MediaAsset> findForDimensionBackfill(int limit, int maxAttempts, java.time.Instant createdBefore) {
		// Newest first: recent posts are the ones being scrolled in the feed right now.
		return repository
			.findForDimensionBackfill(MediaStorageProvider.S3, com.socialcoisa.rede.media.domain.MediaDeleteStatus.NONE,
				maxAttempts, createdBefore, PageRequest.of(0, Math.max(1, limit)))
			.stream()
			.map(MediaAssetJpaEntity::toDomain)
			.toList();
	}
}
