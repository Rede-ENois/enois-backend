package com.socialcoisa.rede.media.adapters.out.storage;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import com.socialcoisa.rede.media.application.port.out.MediaStorageGateway;
import com.socialcoisa.rede.media.domain.MediaAsset;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

class ImageKitMediaStorageGateway implements MediaStorageGateway {

	private static final Logger log = LoggerFactory.getLogger(ImageKitMediaStorageGateway.class);

	private final RestClient client;

	ImageKitMediaStorageGateway(RestClient client) {
		this.client = client;
	}

	@Override
	@Retry(name = "mediaStorage")
	@CircuitBreaker(name = "mediaStorage")
	@Bulkhead(name = "mediaStorage")
	public void purgeCdn(MediaAsset asset) {
		try {
			client.post()
				.uri("/files/purge")
				.body(Map.of("filePath", asset.objectKey()))
				.retrieve()
				.toBodilessEntity();
			log.info("CDN purge requested: {}", asset.objectKey());
		} catch (Exception e) {
			log.warn("CDN purge failed {}: {}", asset.objectKey(), e.getMessage());
			throw e;
		}
	}

	@Override
	@Retry(name = "mediaStorage")
	@CircuitBreaker(name = "mediaStorage")
	@Bulkhead(name = "mediaStorage")
	public void deleteObject(MediaAsset asset) {
		try {
			client.delete()
				.uri("/files/by-path/{path}", asset.objectKey())
				.retrieve()
				.toBodilessEntity();
			log.info("Object deleted: {}", asset.objectKey());
		} catch (Exception e) {
			log.warn("Object delete failed {}: {}", asset.objectKey(), e.getMessage());
			throw e;
		}
	}
}
