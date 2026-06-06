package com.socialcoisa.rede.mentoring.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataMentorJpaRepository extends JpaRepository<MentorJpaEntity, String> {

	List<MentorJpaEntity> findAllByOrderByCreatedAtDesc();

	@Query("SELECT m FROM MentorJpaEntity m WHERE LOWER(m.expertise) LIKE LOWER(CONCAT('%', :expertise, '%')) ORDER BY m.rating DESC")
	List<MentorJpaEntity> findByExpertise(@Param("expertise") String expertise);

}
