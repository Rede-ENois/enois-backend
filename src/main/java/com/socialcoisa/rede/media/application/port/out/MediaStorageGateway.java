package com.socialcoisa.rede.media.application.port.out;

import com.socialcoisa.rede.media.domain.MediaAsset;

public interface MediaStorageGateway {

	void purgeCdn(MediaAsset asset);

	void deleteObject(MediaAsset asset);
}
