package com.socialcoisa.rede.events.adapters.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataEventAttendeeJpaRepository extends JpaRepository<EventAttendeeJpaEntity, Long> {

	boolean existsByEventIdAndUserId(String eventId, String userId);

	void deleteByEventIdAndUserId(String eventId, String userId);

	int countByEventId(String eventId);

}
