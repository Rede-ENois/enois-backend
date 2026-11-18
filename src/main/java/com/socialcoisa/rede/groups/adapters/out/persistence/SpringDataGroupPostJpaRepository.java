package com.socialcoisa.rede.groups.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.groups.domain.GroupPostStatus;

interface SpringDataGroupPostJpaRepository extends JpaRepository<GroupPostJpaEntity, String> {

	@Query("""
		SELECT p FROM GroupPostJpaEntity p
		WHERE p.groupId = :groupId AND p.status = :status
		  AND (:topicId IS NULL OR p.topicId = :topicId)
		  AND (:before IS NULL OR p.createdAt < :before)
		ORDER BY p.createdAt DESC""")
	List<GroupPostJpaEntity> list(@Param("groupId") String groupId, @Param("status") GroupPostStatus status,
			@Param("topicId") String topicId, @Param("before") Instant before, Pageable pageable);

	java.util.Optional<GroupPostJpaEntity> findFirstByGroupIdAndPinnedTrueAndStatus(String groupId,
			GroupPostStatus status);

	/** Um único fixado por grupo: desafixa o anterior na mesma transação do novo. */
	@Modifying
	@Query("UPDATE GroupPostJpaEntity p SET p.pinned = false WHERE p.groupId = :groupId AND p.pinned = true")
	int clearPinned(@Param("groupId") String groupId);

	@Modifying
	@Query("DELETE FROM GroupPostJpaEntity p WHERE p.groupId = :groupId")
	int deleteByGroupId(@Param("groupId") String groupId);

	long countByGroupId(String groupId);

	List<GroupPostJpaEntity> findByGroupId(String groupId);
}
