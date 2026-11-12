package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCalendarParticipantJpaRepository extends JpaRepository<CalendarParticipantJpaEntity, String> {

	Optional<CalendarParticipantJpaEntity> findByEntryIdAndUserId(String entryId, String userId);

	List<CalendarParticipantJpaEntity> findByEntryIdOrderByInvitedAtAsc(String entryId);

	List<CalendarParticipantJpaEntity> findByUserIdAndEntryIdIn(String userId, List<String> entryIds);

}
