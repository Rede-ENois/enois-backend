package com.socialcoisa.rede.media.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.media.domain.MediaAsset;

public interface MediaAssetRepository {

	MediaAsset save(MediaAsset asset);

	Optional<MediaAsset> findById(String id);

	List<MediaAsset> findByIds(Collection<String> ids);
}
