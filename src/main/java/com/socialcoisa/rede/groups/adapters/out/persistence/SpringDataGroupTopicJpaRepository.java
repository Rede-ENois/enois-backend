package com.socialcoisa.rede.groups.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataGroupTopicJpaRepository extends JpaRepository<GroupTopicJpaEntity, String> {

	List<GroupTopicJpaEntity> findByGroupIdOrderByPositionAsc(String groupId);

	boolean existsByGroupIdAndNameIgnoreCase(String groupId, String name);

	@Query("select coalesce(max(t.position), 0) from GroupTopicJpaEntity t where t.groupId = :groupId")
	int maxPosition(@Param("groupId") String groupId);

	@Modifying
	@Query("DELETE FROM GroupTopicJpaEntity t WHERE t.groupId = :groupId")
	int deleteByGroupId(@Param("groupId") String groupId);
}
