package com.socialcoisa.rede.colagem.adapters.out.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataColagemReactionJpaRepository extends JpaRepository<ColagemReactionJpaEntity, String> {

	void deleteByPostIdAndUserId(String postId, String userId);

	List<ColagemReactionJpaEntity> findByUserIdAndPostIdIn(String userId, Collection<String> postIds);

	@Query("""
		select r.postId as postId, r.reaction as reaction, count(r.id) as total
		from ColagemReactionJpaEntity r
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
