package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataNodeKeyJpaRepository extends JpaRepository<NodeKeyJpaEntity, String> {

	@Query("select k from NodeKeyJpaEntity k where k.nodeId = :nodeId and k.revokedAt is null")
	Optional<NodeKeyJpaEntity> findActiveByNode(@Param("nodeId") String nodeId);

	@Query("select k from NodeKeyJpaEntity k where k.nodeId in :nodeIds and k.revokedAt is null")
	List<NodeKeyJpaEntity> findActiveByNodes(@Param("nodeIds") Collection<String> nodeIds);

	Optional<NodeKeyJpaEntity> findByKeyHash(String keyHash);

	@Modifying
	@Query("update NodeKeyJpaEntity k set k.revokedAt = :now, k.revokedBy = :actor "
			+ "where k.nodeId = :nodeId and k.revokedAt is null")
	int revokeActiveByNode(@Param("nodeId") String nodeId, @Param("actor") String actor, @Param("now") Instant now);

	@Modifying
	@Query("update NodeKeyJpaEntity k set k.revokedAt = :now, k.revokedBy = :actor "
			+ "where k.id = :id and k.revokedAt is null")
	int revokeById(@Param("id") String id, @Param("actor") String actor, @Param("now") Instant now);

	@Modifying
	@Query("update NodeKeyJpaEntity k set k.lastUsedAt = :now where k.id = :id")
	int touchLastUsed(@Param("id") String id, @Param("now") Instant now);
}
