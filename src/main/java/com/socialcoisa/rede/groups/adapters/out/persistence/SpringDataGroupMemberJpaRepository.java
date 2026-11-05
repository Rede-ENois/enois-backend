package com.socialcoisa.rede.groups.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataGroupMemberJpaRepository extends JpaRepository<GroupMemberJpaEntity, String> {

	Optional<GroupMemberJpaEntity> findByGroupIdAndUserId(String groupId, String userId);

	List<GroupMemberJpaEntity> findByGroupIdOrderByJoinedAtAsc(String groupId);

	List<GroupMemberJpaEntity> findByUserIdOrderByJoinedAtDesc(String userId);

	long countByGroupId(String groupId);

	void deleteByGroupIdAndUserId(String groupId, String userId);
}
