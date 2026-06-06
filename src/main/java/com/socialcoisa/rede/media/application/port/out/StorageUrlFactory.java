package com.socialcoisa.rede.media.application.port.out;

public interface StorageUrlFactory {

	String objectKey(String mediaId, String fileName);

	String cdnUrl(String objectKey);

	String uploadUrl(String objectKey);
}
