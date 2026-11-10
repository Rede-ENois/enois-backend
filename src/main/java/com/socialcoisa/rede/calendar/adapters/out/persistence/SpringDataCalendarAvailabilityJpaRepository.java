package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataCalendarAvailabilityJpaRepository
		extends JpaRepository<CalendarAvailabilityJpaEntity, String> {

	List<CalendarAvailabilityJpaEntity> findByUserIdOrderByDayOfWeekAscStartsAtAsc(String userId);

	/** A substituição da semana: apaga a da pessoa, não a de mais ninguém. */
	@Modifying
	@Query("delete from CalendarAvailabilityJpaEntity w where w.userId = :userId")
	void deleteByUserId(@Param("userId") String userId);

}