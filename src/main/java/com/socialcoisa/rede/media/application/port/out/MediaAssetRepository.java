package com.socialcoisa.rede.media.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaStorageProvider;

public interface MediaAssetRepository {

	MediaAsset save(MediaAsset asset);

	Optional<MediaAsset> findById(String id);

	List<MediaAsset> findByIds(Collection<String> ids);

	/** Proximo lote da fase 1 (copiar para o S3), priorizando quem errou menos vezes. */
	List<MediaAsset> findForS3Copy(int limit, Instant before, int maxAttempts);

	/**
	 * Claim ATÔMICO da cópia (migração oportunista, segura entre réplicas): só devolve true para
	 * quem gravou o claim — claims velhos expiram para o caso de o pod morrer no meio.
	 */
	boolean claimForS3Copy(String id, Instant now, Instant staleBefore, int maxAttempts);

	/** Proximo lote da fase 2 (flip): copia pronta e cdn_url ainda no provedor antigo. */
	List<MediaAsset> findForS3Flip(int limit);

	long countByStorageProvider(MediaStorageProvider provider);

	long countMigratedToS3();

	long countWithMigrationError();

	/** Ultimo erro registrado (ex.: AccessDenied do S3), para diagnostico pelo status publico. */
	Optional<String> lastMigrationError();

	long countActiveOnS3();

	long countAll();

	/** Stores the measured pixel size with a targeted update, so it never races a full save. */
	void recordDimensions(String id, int width, int height);

	/** One more failed dimension read; the backfill stops picking the asset at the attempts cap. */
	void recordDimensionFailure(String id);

	/** Next backfill batch: images on S3, not deleted, not measured yet and under the attempts cap. */
	List<MediaAsset> findForDimensionBackfill(int limit, int maxAttempts, Instant createdBefore);
}
