package com.socialcoisa.rede.suggestions.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.suggestions.domain.SuggestionStatus;

interface SpringDataSuggestionJpaRepository extends JpaRepository<SuggestionJpaEntity, String> {

	@Query("""
		select s from SuggestionJpaEntity s
		where (:status is null or s.status = :status)
		order by s.createdAt desc
		""")
	List<SuggestionJpaEntity> findFiltered(@Param("status") SuggestionStatus status, Pageable pageable);

	List<SuggestionJpaEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

	long countByUserIdAndCreatedAtAfter(String userId, Instant since);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update SuggestionJpaEntity s
		set s.status = :to, s.reviewedBy = :reviewedBy, s.reviewedAt = :at
		where s.id = :id and s.status = :from
		""")
	int transition(@Param("id") String id, @Param("from") SuggestionStatus from, @Param("to") SuggestionStatus to,
			@Param("reviewedBy") String reviewedBy, @Param("at") Instant at);
}
