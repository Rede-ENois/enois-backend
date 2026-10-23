package com.socialcoisa.rede.media.adapters.out.storage;

import java.io.InputStream;

import org.springframework.util.StringUtils;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;

/** Leitura/escrita do objeto no bucket — usada pelo proxy `/storage` do proprio app. */
class S3MediaObjectStore implements MediaObjectStore {

	private final S3Client client;
	private final String bucket;

	S3MediaObjectStore(S3Client client, String bucket) {
		this.client = client;
		this.bucket = bucket;
	}

	@Override
	public StoredObject read(String key, String range) {
		GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(bucket).key(key);
		if (StringUtils.hasText(range)) {
			request.range(range);
		}
		ResponseInputStream<GetObjectResponse> body = client.getObject(request.build());
		GetObjectResponse response = body.response();
		return new StoredObject(body, response.contentLength() == null ? 0 : response.contentLength(),
			response.contentType(), response.contentRange(), response.eTag());
	}

	@Override
	public void write(String key, String contentType, long length, InputStream body) {
		client.putObject(builder -> {
			builder.bucket(bucket).key(key).contentLength(length);
			if (StringUtils.hasText(contentType)) {
				builder.contentType(contentType);
			}
		}, RequestBody.fromInputStream(body, length));
	}
}
