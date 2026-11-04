package com.socialcoisa.rede.media.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Measures images stored before dimensions were captured on upload, in small rounds. A failed read is
 * counted on the asset, so an object that cannot be decoded stops being picked after a few rounds.
 *
 * On by default; `REDE_MEDIA_DIMENSIONS_BACKFILL_ENABLED=false` turns it off.
 */
@Component
@ConditionalOnProperty(name = "rede.media.dimensions.backfill.enabled", havingValue = "true", matchIfMissing = true)
class MediaDimensionsBackfillWorker {

	private static final Logger log = LoggerFactory.getLogger(MediaDimensionsBackfillWorker.class);

	private final MediaDimensionsService service;
	private final int batchSize;

	MediaDimensionsBackfillWorker(MediaDimensionsService service,
			@Value("${rede.media.dimensions.backfill.batch-size:20}") int batchSize) {
		this.service = service;
		this.batchSize = Math.max(1, Math.min(batchSize, 100));
	}

	// ponytail: the batch query scans media_assets for `width is null`; add an index if the table grows large.
	@Scheduled(fixedDelayString = "${rede.media.dimensions.backfill.delay-ms:60000}",
		initialDelayString = "${rede.media.dimensions.backfill.initial-delay-ms:90000}")
	void run() {
		try {
			int filled = service.backfill(batchSize);
			if (filled > 0) {
				log.info("media_dimensions_backfill filled={}", filled);
			}
		}
		catch (Exception exception) {
			log.warn("media_dimensions_backfill_failed error={}", exception.getClass().getSimpleName());
		}
	}
}
