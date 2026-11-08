package com.socialcoisa.rede.mentoring.adapters.out.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMentorSlotJpaRepository extends JpaRepository<MentorSlotJpaEntity, String> {

	List<MentorSlotJpaEntity> findByMentorIdOrderByStartsAtAsc(String mentorId);

	/** Horários de várias sessões numa consulta só (a lista de sessões do mentorado). */
	List<MentorSlotJpaEntity> findByIdIn(Collection<String> ids);

}
