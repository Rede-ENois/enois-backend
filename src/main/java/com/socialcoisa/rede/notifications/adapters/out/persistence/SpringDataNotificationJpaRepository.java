package com.socialcoisa.rede.notifications.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Modifying;

import com.socialcoisa.rede.notifications.domain.NotificationContext;

interface SpringDataNotificationJpaRepository extends JpaRepository<NotificationJpaEntity, String> {

	/**
	 * Page of the person's notifications. A null context keeps every kind; PROFESSIONAL keeps the
	 * work kinds, SOCIAL the rest, and both keep the account-wide (shared) kinds.
	 */
	@Query("SELECT n FROM NotificationJpaEntity n WHERE n.userId = :userId AND (:before IS NULL OR n.createdAt < :before)"
		+ " AND (:context IS NULL OR n.kind IN " + NotificationContext.SHARED_KINDS_JPQL
		+ " OR (:context = 'PROFESSIONAL' AND n.kind IN " + NotificationContext.PROFESSIONAL_KINDS_JPQL + ")"
		+ " OR (:context = 'SOCIAL' AND n.kind NOT IN " + NotificationContext.PROFESSIONAL_KINDS_JPQL + "))"
		+ " ORDER BY n.createdAt DESC")
	List<NotificationJpaEntity> findByUserId(@Param("userId") String userId, @Param("before") Instant before,
			@Param("context") String context, Pageable pageable);

	/**
	 * Unread count per (kind, actor) of the person, with the same context filter as
	 * {@link #findByUserId}; rows are {@code [kind, actorId, count]}. The actor stays in the
	 * grouping so the caller can drop blocked actors without loading the notifications.
	 */
	@Query("SELECT n.kind, n.actorId, COUNT(n) FROM NotificationJpaEntity n WHERE n.userId = :userId AND n.read = false"
		+ " AND (:context IS NULL OR n.kind IN " + NotificationContext.SHARED_KINDS_JPQL
		+ " OR (:context = 'PROFESSIONAL' AND n.kind IN " + NotificationContext.PROFESSIONAL_KINDS_JPQL + ")"
		+ " OR (:context = 'SOCIAL' AND n.kind NOT IN " + NotificationContext.PROFESSIONAL_KINDS_JPQL + "))"
		+ " GROUP BY n.kind, n.actorId")
	List<Object[]> countUnreadByKindAndActor(@Param("userId") String userId, @Param("context") String context);

	java.util.Optional<NotificationJpaEntity> findFirstByUserIdAndGroupKeyAndReadFalse(String userId, String groupKey);

	@Modifying
	@Query(value = "UPDATE NotificationJpaEntity n SET n.read = true WHERE n.userId = :userId")
	@Transactional
	void markAllReadByUserId(@Param("userId") String userId);

}
