package com.socialcoisa.rede.media.adapters.out.storage;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import com.socialcoisa.rede.media.application.port.out.StorageUrlFactory;

@Component
@ConditionalOnProperty("rede.media.imagekit.url-endpoint")
class ImageKitStorageUrlFactory implements StorageUrlFactory {

	private final String urlEndpoint;

	ImageKitStorageUrlFactory(ImageKitProperties properties) {
		String url = properties.getUrlEndpoint();
		this.urlEndpoint = url.endsWith("/") ? url : url + "/";
	}

	@Override
	public String objectKey(String mediaId, String fileName) {
		return "media/" + mediaId + "/" + UriUtils.encodePathSegment(fileName, StandardCharsets.UTF_8);
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
