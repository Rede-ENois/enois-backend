package com.socialcoisa.rede.groups.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.groups.domain.GroupStatus;

interface SpringDataGroupMemberJpaRepository extends JpaRepository<GroupMemberJpaEntity, String> {

	Optional<GroupMemberJpaEntity> findByGroupIdAndUserId(String groupId, String userId);

	List<GroupMemberJpaEntity> findByGroupIdOrderByJoinedAtAsc(String groupId);

	/** Pares (grupo, papel) da pessoa, mais recentes primeiro, já sem os status pedidos. */
	@Query("""
		SELECT g, m.role FROM GroupMemberJpaEntity m, GroupJpaEntity g
		WHERE g.id = m.groupId AND m.userId = :userId AND g.status NOT IN :excluded
		ORDER BY m.joinedAt DESC, m.id DESC""")
	List<Object[]> findMemberships(@Param("userId") String userId,
			@Param("excluded") Collection<GroupStatus> excluded, Pageable page);

	@Query("""
		SELECT m.groupId, COUNT(m) FROM GroupMemberJpaEntity m
		WHERE m.groupId IN :groupIds
		GROUP BY m.groupId""")
	List<Object[]> countByGroupIds(@Param("groupIds") Collection<String> groupIds);

	long countByGroupId(String groupId);

	void deleteByGroupIdAndUserId(String groupId, String userId);
}
