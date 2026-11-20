package com.socialcoisa.rede.communities.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCommunityChannelJpaRepository extends JpaRepository<CommunityChannelJpaEntity, String> {

	List<CommunityChannelJpaEntity> findByCommunityIdOrderByCreatedAtAsc(String communityId);

	boolean existsByCommunityIdAndSlug(String communityId, String slug);

	int countByCommunityId(String communityId);

	void deleteByCommunityId(String communityId);
}
