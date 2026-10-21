package com.socialcoisa.rede.events.adapters.out.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataEventAttendeeJpaRepository extends JpaRepository<EventAttendeeJpaEntity, Long> {

	boolean existsByEventIdAndUserId(String eventId, String userId);

	void deleteByEventIdAndUserId(String eventId, String userId);

	/** Apaga todas as presencas de um evento (sanitizacao: some o vinculo com cada pessoa). */
	void deleteByEventId(String eventId);

	int countByEventId(String eventId);

	/** Só os IDs (a sanitização não precisa de nada além disso). */
	@Query("select a.userId from EventAttendeeJpaEntity a where a.eventId = :eventId")
	List<String> findUserIdsByEventId(String eventId);

	@Query("SELECT a.eventId FROM EventAttendeeJpaEntity a WHERE a.userId = :userId AND a.eventId IN :eventIds")
	List<String> findEventIdsByUserIdAndEventIdIn(@Param("userId") String userId,
			@Param("eventIds") Collection<String> eventIds);
}
