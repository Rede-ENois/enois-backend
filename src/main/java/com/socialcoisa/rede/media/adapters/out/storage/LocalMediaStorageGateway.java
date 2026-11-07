package com.socialcoisa.rede.media.adapters.out.storage;

import com.socialcoisa.rede.media.application.port.out.MediaStorageGateway;
import com.socialcoisa.rede.media.domain.MediaAsset;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

/** Exclusão no storage local de dev: apaga o arquivo; não existe CDN para purgar. */
class LocalMediaStorageGateway implements MediaStorageGateway {

	private final LocalMediaObjectStore store;

	LocalMediaStorageGateway(LocalMediaObjectStore store) {
		this.store = store;
	}

	@Override
	@Retry(name = "mediaStorage")
	@CircuitBreaker(name = "mediaStorage")
	@Bulkhead(name = "mediaStorage")
	public void purgeCdn(MediaAsset asset) {
		// O modo local não tem CDN: nada a invalidar.
	}

	@Override
	@Retry(name = "mediaStorage")
	@CircuitBreaker(name = "mediaStorage")
	@Bulkhead(name = "mediaStorage")
	public void deleteObject(MediaAsset asset) {
		store.delete(asset.objectKey());
	}
}
