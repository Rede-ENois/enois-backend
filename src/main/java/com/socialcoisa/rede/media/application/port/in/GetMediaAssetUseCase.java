package com.socialcoisa.rede.media.application.port.in;

import com.socialcoisa.rede.media.application.MediaAssetView;

public interface GetMediaAssetUseCase {

	MediaAssetView getMedia(String mediaId);
}
