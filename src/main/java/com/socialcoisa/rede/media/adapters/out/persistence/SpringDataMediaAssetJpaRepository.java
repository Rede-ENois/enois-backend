package com.socialcoisa.rede.media.adapters.out.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMediaAssetJpaRepository extends JpaRepository<MediaAssetJpaEntity, String> {

	List<MediaAssetJpaEntity> findByIdIn(Collection<String> ids);
}
