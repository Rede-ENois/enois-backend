package com.socialcoisa.rede.mentoring.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataMentorJpaRepository extends JpaRepository<MentorJpaEntity, String> {

	@Query("SELECT m FROM MentorJpaEntity m WHERE (:before IS NULL OR m.createdAt < :before) ORDER BY m.createdAt DESC")
	List<MentorJpaEntity> findAllBefore(@Param("before") Instant before, Pageable pageable);

	Optional<MentorJpaEntity> findByUserId(String userId);

	@Query("SELECT m FROM MentorJpaEntity m WHERE LOWER(m.expertise) LIKE LOWER(CONCAT('%', :expertise, '%')) "
			+ "AND (:before IS NULL OR m.createdAt < :before) ORDER BY m.rating DESC")
	List<MentorJpaEntity> findByExpertise(@Param("expertise") String expertise, @Param("before") Instant before,
			Pageable pageable);

}
