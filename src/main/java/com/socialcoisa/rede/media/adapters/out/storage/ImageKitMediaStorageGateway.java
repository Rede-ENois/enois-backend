package com.socialcoisa.rede.media.adapters.out.storage;

import java.util.Base64;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.socialcoisa.rede.media.application.port.out.MediaStorageGateway;
import com.socialcoisa.rede.media.domain.MediaAsset;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

@Component
@ConditionalOnProperty("rede.media.imagekit.url-endpoint")
class ImageKitMediaStorageGateway implements MediaStorageGateway {

	private final RestClient restClient;
	private final ImageKitStorageUrlFactory urlFactory;

	ImageKitMediaStorageGateway(ImageKitProperties properties, ImageKitStorageUrlFactory urlFactory) {
		this.urlFactory = urlFactory;
		String auth = "Basic " + Base64.getEncoder().encodeToString((properties.getPrivateKey() + ":").getBytes());
		this.restClient = RestClient.builder()
			.baseUrl("https://api.imagekit.io/v1")
			.defaultHeader(HttpHeaders.AUTHORIZATION, auth)
			.build();
	}

	@Override
	@Retry(name = "mediaStorage")
	@CircuitBreaker(name = "mediaStorage")
	@Bulkhead(name = "mediaStorage")
	public void purgeCdn(MediaAsset asset) {
		String cdnUrl = urlFactory.cdnUrl(asset.objectKey());
		restClient.post()
			.uri("/files/purge")
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("url", cdnUrl))
			.retrieve()
			.toBodilessEntity();
	}

	@Override
	@Retry(name = "mediaStorage")
	@CircuitBreaker(name = "mediaStorage")
	@Bulkhead(name = "mediaStorage")
	public void deleteObject(MediaAsset asset) {
		restClient.delete()
			.uri("/files/{fileId}", asset.id())
			.retrieve()
			.toBodilessEntity();
	}
}
