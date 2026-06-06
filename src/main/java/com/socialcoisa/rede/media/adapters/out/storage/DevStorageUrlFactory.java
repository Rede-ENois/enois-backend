package com.socialcoisa.rede.media.adapters.out.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import com.socialcoisa.rede.media.application.port.out.StorageUrlFactory;

@Component
@ConditionalOnMissingBean(StorageUrlFactory.class)
class DevStorageUrlFactory implements StorageUrlFactory {

	@Override
	public String objectKey(String mediaId, String fileName) {
		return "dev/" + mediaId + "/" + UriUtils.encodePathSegment(fileName, java.nio.charset.StandardCharsets.UTF_8);
	}

	@Override
	public String cdnUrl(String objectKey) {
		return "/dev/cdn/" + objectKey;
	}

	@Override
	public String uploadUrl(String objectKey) {
		return "/dev/uploads/" + objectKey;
	}
}
