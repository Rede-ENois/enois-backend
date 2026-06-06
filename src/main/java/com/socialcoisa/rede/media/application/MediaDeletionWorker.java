package com.socialcoisa.rede.media.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaDeletionJobRepository;
import com.socialcoisa.rede.media.application.port.out.MediaStorageGateway;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaDeletionJob;

@Component
class MediaDeletionWorker {

	private final MediaDeletionJobRepository jobRepository;
	private final MediaAssetRepository assetRepository;
	private final MediaStorageGateway storageGateway;
	private final Clock clock;

	MediaDeletionWorker(MediaDeletionJobRepository jobRepository, MediaAssetRepository assetRepository,
			MediaStorageGateway storageGateway, Clock clock) {
		this.jobRepository = jobRepository;
		this.assetRepository = assetRepository;
		this.storageGateway = storageGateway;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${rede.media.deletion-worker-delay-ms:5000}")
	@Transactional
	void processPendingJobs() {
		List<MediaDeletionJob> jobs = jobRepository.findPending(clock.instant(), 50);
		for (MediaDeletionJob job : jobs) {
			try {
				MediaAsset asset = assetRepository.findById(job.mediaId()).orElse(null);
				if (asset == null) {
					jobRepository.complete(job.mediaId(), clock.instant());
					continue;
				}
				storageGateway.purgeCdn(asset);
				asset.markCdnPurged();
				storageGateway.deleteObject(asset);
				asset.markObjectDeleted(clock.instant());
				assetRepository.save(asset);
				jobRepository.complete(asset.id(), clock.instant());
			}
			catch (RuntimeException exception) {
				jobRepository.fail(job.mediaId(), exception.getMessage(), clock.instant().plus(backoff(job.attempts())));
			}
		}
	}

	private Duration backoff(int attempts) {
		int seconds = Math.min(300, Math.max(5, (attempts + 1) * 10));
		return Duration.ofSeconds(seconds);
	}
}
