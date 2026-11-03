package com.socialcoisa.rede.media.domain;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.UserId;

public class MediaAsset {

	private final String id;
	private final UserId ownerId;
	private final String fileName;
	private final String mimeType;
	private final long size;
	private final String purpose;
	private final String objectKey;
	/** O endereço que o app usa hoje. So muda no flip, depois de a cópia no S3 ser verificada. */
	private String cdnUrl;
	private final Instant createdAt;
	private final Instant expiresAt;
	private MediaStatus status;
	private MediaDeleteStatus deleteStatus;
	private Instant deletedAt;
	/** Onde o objeto está de fato: IMAGEKIT ate o worker copiar; S3 depois da copia. */
	private MediaStorageProvider storageProvider;
	/** Endereco publico do objeto no S3 (null ate a copia). */
	private String s3CdnUrl;
	private Instant s3MigratedAt;
	private int s3MigrationAttempts;
	private String s3LastError;
	/** Pixel size as displayed; null for non-images and for images not measured yet. */
	private final Integer width;
	private final Integer height;
	/** Failed dimension reads by the backfill worker (it gives up after a few). */
	private final int dimensionAttempts;

	private MediaAsset(String id, UserId ownerId, String fileName, String mimeType, long size, String purpose,
			String objectKey, String cdnUrl, MediaStatus status, MediaDeleteStatus deleteStatus, Instant createdAt,
			Instant expiresAt, Instant deletedAt, MediaStorageProvider storageProvider, String s3CdnUrl,
			Instant s3MigratedAt, int s3MigrationAttempts, String s3LastError, Integer width, Integer height,
			int dimensionAttempts) {
		this.id = id;
		this.ownerId = ownerId;
		this.fileName = fileName;
		this.mimeType = mimeType;
		this.size = size;
		this.purpose = purpose;
		this.objectKey = objectKey;
		this.cdnUrl = cdnUrl;
		this.status = status;
		this.deleteStatus = deleteStatus;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.deletedAt = deletedAt;
		this.storageProvider = storageProvider;
		this.s3CdnUrl = s3CdnUrl;
		this.s3MigratedAt = s3MigratedAt;
		this.s3MigrationAttempts = s3MigrationAttempts;
		this.s3LastError = s3LastError;
		this.width = width;
		this.height = height;
		this.dimensionAttempts = dimensionAttempts;
	}

	public static MediaAsset pending(String id, UserId ownerId, String fileName, String mimeType, long size,
			String purpose, String objectKey, String cdnUrl, Instant createdAt, Instant expiresAt) {
		return pending(id, ownerId, fileName, mimeType, size, purpose, objectKey, cdnUrl, createdAt, expiresAt,
			MediaStorageProvider.IMAGEKIT);
	}

	/**
	 * Upload novo: em S3 o endereco ja nasce apontando para o bucket (s3CdnUrl = cdnUrl); em
	 * ImageKit a copia fica pendente para o worker.
	 */
	public static MediaAsset pending(String id, UserId ownerId, String fileName, String mimeType, long size,
			String purpose, String objectKey, String cdnUrl, Instant createdAt, Instant expiresAt,
			MediaStorageProvider provider) {
		boolean onS3 = provider == MediaStorageProvider.S3;
		return new MediaAsset(id, ownerId, fileName, mimeType, size, purpose, objectKey, cdnUrl, MediaStatus.PENDING,
			MediaDeleteStatus.NONE, createdAt, expiresAt, null, provider, onS3 ? cdnUrl : null,
			onS3 ? createdAt : null, 0, null, null, null, 0);
	}

	public static MediaAsset restore(String id, UserId ownerId, String fileName, String mimeType, long size,
			String purpose, String objectKey, String cdnUrl, MediaStatus status, MediaDeleteStatus deleteStatus,
			Instant createdAt, Instant expiresAt, Instant deletedAt, MediaStorageProvider storageProvider,
			String s3CdnUrl, Instant s3MigratedAt, int s3MigrationAttempts, String s3LastError, Integer width,
			Integer height, int dimensionAttempts) {
		return new MediaAsset(id, ownerId, fileName, mimeType, size, purpose, objectKey, cdnUrl, status, deleteStatus,
			createdAt, expiresAt, deletedAt, storageProvider, s3CdnUrl, s3MigratedAt, s3MigrationAttempts,
			s3LastError, width, height, dimensionAttempts);
	}

	public boolean belongsTo(UserId userId) {
		return ownerId.equals(userId);
	}

	public boolean requestDeletion(Instant deletedAt) {
		if (deleteStatus != MediaDeleteStatus.NONE && deleteStatus != MediaDeleteStatus.FAILED) {
			return false;
		}
		this.deleteStatus = MediaDeleteStatus.REQUESTED;
		this.deletedAt = deletedAt;
		return true;
	}

	public void markCdnPurged() {
		if (deleteStatus == MediaDeleteStatus.REQUESTED) {
			deleteStatus = MediaDeleteStatus.CDN_PURGED;
		}
	}

	public void markObjectDeleted(Instant deletedAt) {
		deleteStatus = MediaDeleteStatus.OBJECT_DELETED;
		this.deletedAt = deletedAt;
	}

	public void markDeletionFailed() {
		deleteStatus = MediaDeleteStatus.FAILED;
	}

	// --- Migracao ImageKit -> S3 (duas fases; ver docs/MIDIA-S3.md) ---

	/** Ainda falta copiar o objeto para o S3 (ou a copia anterior falhou e vale tentar de novo). */
	public boolean needsS3Copy() {
		return storageProvider == MediaStorageProvider.IMAGEKIT && s3MigratedAt == null;
	}

	/** Copia concluida e verificada pelo S3: o objeto existe no bucket, mas o app ainda le o antigo. */
	public void markS3Copy(String s3CdnUrl, Instant now) {
		this.storageProvider = MediaStorageProvider.S3;
		this.s3CdnUrl = s3CdnUrl;
		this.s3MigratedAt = now;
		this.s3LastError = null;
	}

	public void markS3MigrationFailure(String error, Instant now) {
		this.s3MigrationAttempts++;
		if (error != null) {
			this.s3LastError = error.length() > 300 ? error.substring(0, 300) : error;
		}
	}

	/** O cdn_url atual ainda e do ImageKit e ja existe copia verificada no S3. */
	public boolean canActivateS3() {
		return storageProvider == MediaStorageProvider.S3 && s3CdnUrl != null && !s3CdnUrl.equals(cdnUrl);
	}

	/**
	 * Fase 2: aponta o cdn_url para o S3. So deve ser chamado depois de uma leitura publica
	 * bem-sucedida (o servico confere antes); o objeto no ImageKit continua intacto.
	 */
	public void activateS3() {
		if (s3CdnUrl == null) {
			throw new IllegalStateException("Asset sem copia no S3.");
		}
		this.cdnUrl = s3CdnUrl;
	}

	public String id() {
		return id;
	}

	public UserId ownerId() {
		return ownerId;
	}

	public String fileName() {
		return fileName;
	}

	public String mimeType() {
		return mimeType;
	}

	public long size() {
		return size;
	}

	public String purpose() {
		return purpose;
	}

	public String objectKey() {
		return objectKey;
	}

	public String cdnUrl() {
		return cdnUrl;
	}

	public MediaStatus status() {
		return status;
	}

	public MediaDeleteStatus deleteStatus() {
		return deleteStatus;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	public Instant deletedAt() {
		return deletedAt;
	}

	public MediaStorageProvider storageProvider() {
		return storageProvider;
	}

	public String s3CdnUrl() {
		return s3CdnUrl;
	}

	public Instant s3MigratedAt() {
		return s3MigratedAt;
	}

	public int s3MigrationAttempts() {
		return s3MigrationAttempts;
	}

	public String s3LastError() {
		return s3LastError;
	}

	public Integer width() {
		return width;
	}

	public Integer height() {
		return height;
	}

	public int dimensionAttempts() {
		return dimensionAttempts;
	}

	/** Not removed and not on its way out: the only assets a post may still show. */
	public boolean isVisible() {
		return deleteStatus == MediaDeleteStatus.NONE;
	}
}
