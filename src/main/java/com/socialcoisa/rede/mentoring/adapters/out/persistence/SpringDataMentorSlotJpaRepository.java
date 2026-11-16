package com.socialcoisa.rede.mentoring.adapters.out.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataMentorSlotJpaRepository extends JpaRepository<MentorSlotJpaEntity, String> {

	List<MentorSlotJpaEntity> findByMentorIdOrderByStartsAtAsc(String mentorId);

	/** Horários de várias sessões numa consulta só (a lista de sessões do mentorado). */
	List<MentorSlotJpaEntity> findByIdIn(Collection<String> ids);

	/**
	 * Reserva atômica: só marca o horário se ele ainda estiver livre e devolve quantas linhas o
	 * UPDATE alcançou. Duas inscrições correndo juntas leem o mesmo "disponível", mas só o
	 * primeiro UPDATE vê `available = true` — o segundo alcança zero linhas e a reserva dele é
	 * recusada antes de reter qualquer faísca.
	 */
	@Modifying
	@Query("UPDATE MentorSlotJpaEntity s SET s.available = false WHERE s.id = :id AND s.available = true")
	int bookIfFree(@Param("id") String id);

}
