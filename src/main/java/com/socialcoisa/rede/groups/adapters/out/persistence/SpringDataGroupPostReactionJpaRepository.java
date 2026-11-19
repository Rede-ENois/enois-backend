package com.socialcoisa.rede.groups.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


interface SpringDataGroupPostReactionJpaRepository extends JpaRepository<GroupReactionJpaEntity, String> {

	Optional<GroupReactionJpaEntity> findByPostIdAndUserId(String postId, String userId);

	List<GroupReactionJpaEntity> findByUserIdAndPostIdIn(String userId, Collection<String> postIds);

	void deleteByPostIdAndUserId(String postId, String userId);

	long countByPostId(String postId);

	/** Exclusão física das reações de um conjunto de publicações (só a remoção pela plataforma). */
	@Modifying
	@Query("DELETE FROM GroupReactionJpaEntity r WHERE r.postId IN :postIds")
	int deleteByPostIds(@Param("postIds") java.util.Collection<String> postIds);

}
