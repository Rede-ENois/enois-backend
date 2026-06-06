package com.socialcoisa.rede.mentoring.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMentorSlotJpaRepository extends JpaRepository<MentorSlotJpaEntity, String> {

	List<MentorSlotJpaEntity> findByMentorIdOrderByStartsAtAsc(String mentorId);

}
