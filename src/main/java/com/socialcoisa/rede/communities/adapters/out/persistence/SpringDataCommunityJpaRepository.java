package com.socialcoisa.rede.communities.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCommunityJpaRepository extends JpaRepository<CommunityJpaEntity, String> {

	List<CommunityJpaEntity> findByTopicOrderByCreatedAtDesc(String topic);

	List<CommunityJpaEntity> findAllByOrderByCreatedAtDesc();

}
