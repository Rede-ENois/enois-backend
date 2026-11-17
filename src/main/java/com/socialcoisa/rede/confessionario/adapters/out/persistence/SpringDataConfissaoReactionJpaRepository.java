package com.socialcoisa.rede.confessionario.adapters.out.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataConfissaoReactionJpaRepository extends JpaRepository<ConfissaoReactionJpaEntity, String> {

	void deleteByPostIdAndUserId(String postId, String userId);

	List<ConfissaoReactionJpaEntity> findByUserIdAndPostIdIn(String userId, Collection<String> postIds);

	@Query("""
		select r.postId as postId, r.reaction as reaction, count(r.id) as total
		from ConfissaoReactionJpaEntity r
		where r.postId in :postIds
		group by r.postId, r.reaction
		""")
	List<ReactionCountProjection> countByPostIdIn(@Param("postIds") Collection<String> postIds);

	interface ReactionCountProjection {

		String getPostId();

		String getReaction();

		long getTotal();
	}
}
