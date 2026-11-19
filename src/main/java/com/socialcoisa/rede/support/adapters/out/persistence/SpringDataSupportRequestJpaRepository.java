package com.socialcoisa.rede.support.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.support.domain.SupportCategory;
import com.socialcoisa.rede.support.domain.SupportStatus;

import jakarta.persistence.LockModeType;

interface SpringDataSupportRequestJpaRepository extends JpaRepository<SupportRequestJpaEntity, String> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from SupportRequestJpaEntity s where s.id = :id")
	Optional<SupportRequestJpaEntity> findByIdForUpdate(@Param("id") String id);

	@Query("""
		select s from SupportRequestJpaEntity s
		where (:category is null or s.category = :category)
		  and (:status is null or s.status = :status)
		order by s.createdAt desc
		""")
	List<SupportRequestJpaEntity> findFiltered(@Param("category") SupportCategory category,
			@Param("status") SupportStatus status, Pageable pageable);

	long countByUserIdAndCreatedAtAfter(String userId, java.time.Instant since);

	java.util.Optional<SupportRequestJpaEntity> findByGithubIssueNumber(int githubIssueNumber);

	List<SupportRequestJpaEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
