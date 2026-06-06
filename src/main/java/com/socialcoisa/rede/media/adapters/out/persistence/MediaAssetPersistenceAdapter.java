package com.socialcoisa.rede.media.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.domain.MediaAsset;

@Repository
class MediaAssetPersistenceAdapter implements MediaAssetRepository {

	private final SpringDataMediaAssetJpaRepository repository;

	MediaAssetPersistenceAdapter(SpringDataMediaAssetJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public MediaAsset save(MediaAsset asset) {
		return repository.save(MediaAssetJpaEntity.fromDomain(asset)).toDomain();
	}

	@Override
	public Optional<MediaAsset> findById(String id) {
		return repository.findById(id).map(MediaAssetJpaEntity::toDomain);
	}

	@Override
	public List<MediaAsset> findByIds(Collection<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		return repository.findByIdIn(ids).stream().map(MediaAssetJpaEntity::toDomain).toList();
	}
}
