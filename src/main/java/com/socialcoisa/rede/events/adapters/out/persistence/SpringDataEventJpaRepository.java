package com.socialcoisa.rede.events.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataEventJpaRepository extends JpaRepository<EventJpaEntity, String> {

	List<EventJpaEntity> findBySanitizedAtIsNull();

	List<EventJpaEntity> findBySanitizedAtBefore(java.time.Instant threshold);


	@Query("SELECT e FROM EventJpaEntity e WHERE (:before IS NULL OR e.createdAt < :before)"
		+ " AND (:recurringOnly = false OR e.recurrence <> 'NONE') ORDER BY e.createdAt DESC")
	List<EventJpaEntity> findAllBefore(@Param("before") Instant before, @Param("recurringOnly") boolean recurringOnly,
			Pageable pageable);

	@Query("SELECT e FROM EventJpaEntity e WHERE e.format = :format AND (:before IS NULL OR e.createdAt < :before)"
		+ " AND (:recurringOnly = false OR e.recurrence <> 'NONE') ORDER BY e.createdAt DESC")
	List<EventJpaEntity> findByFormat(@Param("format") String format, @Param("before") Instant before,
			@Param("recurringOnly") boolean recurringOnly, Pageable pageable);

	List<EventJpaEntity> findByRecurrenceNot(String recurrence);

	java.util.Optional<EventJpaEntity> findFirstBySeriesIdOrderByCreatedAtDesc(String seriesId);

	boolean existsBySeriesIdAndDate(String seriesId, String date);

	@org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE EventJpaEntity e SET e.recurrence = :recurrence WHERE e.seriesId = :seriesId")
	int updateSeriesRecurrence(@Param("seriesId") String seriesId, @Param("recurrence") String recurrence);

	// In-person events by place: online events never match, even with a stale city on the row.
	@Query("SELECT e FROM EventJpaEntity e WHERE e.state = :state AND (:cityKey IS NULL OR e.cityKey = :cityKey) "
			+ "AND UPPER(e.format) <> 'ONLINE' AND (:format IS NULL OR e.format = :format) "
			+ "AND (:before IS NULL OR e.createdAt < :before) "
			+ "AND (:recurringOnly = false OR e.recurrence <> 'NONE') ORDER BY e.createdAt DESC")
	List<EventJpaEntity> findByPlace(@Param("state") String state, @Param("cityKey") String cityKey,
			@Param("format") String format, @Param("before") Instant before,
			@Param("recurringOnly") boolean recurringOnly, Pageable pageable);

}
