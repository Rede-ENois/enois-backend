package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataPollVoteJpaRepository extends JpaRepository<PollVoteJpaEntity, String> {

	List<PollVoteJpaEntity> findByPostIdInAndUserId(Collection<String> postIds, String userId);

	long countByPostId(String postId);

	/** Rows of (postId, optionPosition, count) — a contagem da enquete inteira numa consulta. */
	@Query("SELECT v.postId, v.optionPosition, COUNT(v) FROM PollVoteJpaEntity v "
		+ "WHERE v.postId IN :postIds GROUP BY v.postId, v.optionPosition")
	List<Object[]> countByPostIdsGroupedByPosition(@Param("postIds") Collection<String> postIds);
}
