package com.socialcoisa.rede.mentoring.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataMentorAvailabilityJpaRepository extends JpaRepository<MentorAvailabilityJpaEntity, String> {

	List<MentorAvailabilityJpaEntity> findByMentorIdOrderByDayOfWeekAscTimeOfDayAsc(String mentorId);

	@Modifying
	@Query("delete from MentorAvailabilityJpaEntity a where a.mentorId = :mentorId")
	void deleteByMentorId(@Param("mentorId") String mentorId);

}
