package com.socialcoisa.rede.media.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.media.domain.MediaDeleteStatus;
import com.socialcoisa.rede.media.domain.MediaStorageProvider;

interface SpringDataMediaAssetJpaRepository extends JpaRepository<MediaAssetJpaEntity, String> {

	List<MediaAssetJpaEntity> findByIdIn(Collection<String> ids);

	boolean existsByIdInAndPurpose(Collection<String> ids, String purpose);

	/** Lote da copia: ainda no ImageKit, upload antigo o bastante, nao apagado e sem estourar tentativas. */
	@Query("select e from MediaAssetJpaEntity e where e.storageProvider = :provider and e.s3MigratedAt is null "
		+ "and e.deleteStatus = :deleteStatus and e.createdAt < :before and e.s3MigrationAttempts < :maxAttempts "
		+ "and (e.s3ClaimedAt is null or e.s3ClaimedAt < :staleBefore) "
		+ "order by e.s3MigrationAttempts asc, e.createdAt asc")
	List<MediaAssetJpaEntity> findForS3Copy(@Param("provider") MediaStorageProvider provider,
		@Param("deleteStatus") MediaDeleteStatus deleteStatus, @Param("before") Instant before,
		@Param("maxAttempts") int maxAttempts, @Param("staleBefore") Instant staleBefore, Pageable pageable);

	/** Assets com copia no S3 verificada e cdn_url ainda no provedor antigo (fase de flip). */
	@Query("select e from MediaAssetJpaEntity e where e.storageProvider = :provider and e.s3CdnUrl is not null "
		+ "and e.cdnUrl <> e.s3CdnUrl and e.deleteStatus = :deleteStatus order by e.createdAt asc")
	List<MediaAssetJpaEntity> findPendingFlip(@Param("provider") MediaStorageProvider provider,
		@Param("deleteStatus") MediaDeleteStatus deleteStatus, Pageable pageable);

	long countByStorageProvider(MediaStorageProvider provider);

	long countByS3MigratedAtIsNotNull();

	long countByS3LastErrorIsNotNull();

	java.util.Optional<MediaAssetJpaEntity> findFirstByS3LastErrorIsNotNullOrderByCreatedAtDesc();

	@Query("select count(e) from MediaAssetJpaEntity e where e.storageProvider = :provider "
		+ "and e.s3CdnUrl is not null and e.cdnUrl = e.s3CdnUrl")
	long countActiveOnProvider(@Param("provider") MediaStorageProvider provider);

	/** Claim DO banco: só uma réplica ganha o direito de copiar este asset agora. */
	@org.springframework.data.jpa.repository.Modifying
	@Query("update MediaAssetJpaEntity e set e.s3ClaimedAt = :now, e.s3MigrationAttempts = e.s3MigrationAttempts + 1 "
		+ "where e.id = :id and e.s3MigratedAt is null and e.s3MigrationAttempts < :maxAttempts "
		+ "and (e.s3ClaimedAt is null or e.s3ClaimedAt < :staleBefore)")
	int claimForS3Copy(@Param("id") String id, @Param("now") Instant now,
		@Param("staleBefore") Instant staleBefore, @Param("maxAttempts") int maxAttempts);

	@org.springframework.data.jpa.repository.Modifying
	@Query("update MediaAssetJpaEntity e set e.width = :width, e.height = :height where e.id = :id")
	int recordDimensions(@Param("id") String id, @Param("width") int width, @Param("height") int height);

	@org.springframework.data.jpa.repository.Modifying
	@Query("update MediaAssetJpaEntity e set e.dimensionAttempts = e.dimensionAttempts + 1 where e.id = :id")
	int recordDimensionFailure(@Param("id") String id);

	@Query("select e from MediaAssetJpaEntity e where e.width is null and e.mimeType like 'image/%' "
		+ "and e.storageProvider = :provider and e.deleteStatus = :deleteStatus "
		+ "and e.dimensionAttempts < :maxAttempts and e.createdAt < :before order by e.createdAt desc")
	List<MediaAssetJpaEntity> findForDimensionBackfill(@Param("provider") MediaStorageProvider provider,
		@Param("deleteStatus") MediaDeleteStatus deleteStatus, @Param("maxAttempts") int maxAttempts,
		@Param("before") Instant before, Pageable pageable);
}
