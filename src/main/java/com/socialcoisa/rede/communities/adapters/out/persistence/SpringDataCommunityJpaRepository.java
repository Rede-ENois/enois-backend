package com.socialcoisa.rede.communities.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataCommunityJpaRepository extends JpaRepository<CommunityJpaEntity, String> {

	@Query("SELECT c FROM CommunityJpaEntity c WHERE (:before IS NULL OR c.createdAt < :before) ORDER BY c.createdAt DESC")
	List<CommunityJpaEntity> findAllBefore(@Param("before") Instant before, Pageable pageable);

	@Query("SELECT c FROM CommunityJpaEntity c WHERE c.topic = :topic AND (:before IS NULL OR c.createdAt < :before) ORDER BY c.createdAt DESC")
	List<CommunityJpaEntity> findByTopic(@Param("topic") String topic, @Param("before") Instant before,
			Pageable pageable);

}
