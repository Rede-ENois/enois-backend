package com.socialcoisa.rede.media.adapters.out.storage;

import org.springframework.web.util.UriUtils;

import com.socialcoisa.rede.media.application.port.out.StorageUrlFactory;

import java.nio.charset.StandardCharsets;

class ImageKitStorageUrlFactory implements StorageUrlFactory {

	private final String urlEndpoint;

	ImageKitStorageUrlFactory(String urlEndpoint) {
		this.urlEndpoint = urlEndpoint.endsWith("/") ? urlEndpoint : urlEndpoint + "/";
	}

	@Override
	public String objectKey(String mediaId, String fileName) {
		return "uploads/" + mediaId + "/" + UriUtils.encodePathSegment(fileName, StandardCharsets.UTF_8);
	}

	@Override
	public String cdnUrl(String objectKey) {
		return urlEndpoint + objectKey;
	}

	@Override
	public String uploadUrl(String objectKey) {
		return urlEndpoint + objectKey;
	}
}
