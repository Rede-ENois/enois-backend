package com.socialcoisa.rede.groups.adapters.out.persistence;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.groups.domain.GroupCommentStatus;

interface SpringDataGroupCommentJpaRepository extends JpaRepository<GroupCommentJpaEntity, String> {

	List<GroupCommentJpaEntity> findByPostIdAndStatusOrderByCreatedAtAsc(String postId, GroupCommentStatus status,
			Pageable pageable);

	long countByPostIdAndStatus(String postId, GroupCommentStatus status);

	@Modifying
	@Query("DELETE FROM GroupCommentJpaEntity c WHERE c.groupId = :groupId")
	int deleteByGroupId(@Param("groupId") String groupId);
}
