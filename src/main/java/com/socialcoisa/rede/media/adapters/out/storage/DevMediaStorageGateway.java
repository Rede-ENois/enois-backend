package com.socialcoisa.rede.media.adapters.out.storage;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.media.application.port.out.MediaStorageGateway;
import com.socialcoisa.rede.media.domain.MediaAsset;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

@Component
class DevMediaStorageGateway implements MediaStorageGateway {

	@Override
	@Retry(name = "mediaStorage")
	@CircuitBreaker(name = "mediaStorage")
	@Bulkhead(name = "mediaStorage")
	public void purgeCdn(MediaAsset asset) {
	}

	@Override
	@Retry(name = "mediaStorage")
	@CircuitBreaker(name = "mediaStorage")
	@Bulkhead(name = "mediaStorage")
	public void deleteObject(MediaAsset asset) {
	}
}
