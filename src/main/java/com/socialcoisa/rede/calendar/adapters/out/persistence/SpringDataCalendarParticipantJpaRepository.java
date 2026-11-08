package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataCalendarParticipantJpaRepository extends JpaRepository<CalendarParticipantJpaEntity, String> {

	Optional<CalendarParticipantJpaEntity> findByEntryIdAndUserId(String entryId, String userId);

	List<CalendarParticipantJpaEntity> findByEntryIdOrderByInvitedAtAsc(String entryId);

	List<CalendarParticipantJpaEntity> findByUserIdAndEntryIdIn(String userId, List<String> entryIds);

	@Query("select p.entryId from CalendarParticipantJpaEntity p where p.userId = :userId")
	List<String> findEntryIdsByUserId(@Param("userId") String userId);

}
