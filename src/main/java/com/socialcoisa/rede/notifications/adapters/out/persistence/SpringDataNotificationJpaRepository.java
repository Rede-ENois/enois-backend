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
	 * Unread count per (kind, actor, href) of the person, with the same context filter as
	 * {@link #findByUserId}; rows are {@code [kind, actorId, href, count]}. The actor stays in the
	 * grouping so the caller can drop blocked actors without loading the notifications, and the
	 * href so it can count per community.
	 */
	@Query("SELECT n.kind, n.actorId, n.href, COUNT(n) FROM NotificationJpaEntity n WHERE n.userId = :userId AND n.read = false"
		+ " AND (:context IS NULL OR n.kind IN " + NotificationContext.SHARED_KINDS_JPQL
		+ " OR (:context = 'PROFESSIONAL' AND n.kind IN " + NotificationContext.PROFESSIONAL_KINDS_JPQL + ")"
		+ " OR (:context = 'SOCIAL' AND n.kind NOT IN " + NotificationContext.PROFESSIONAL_KINDS_JPQL + "))"
		+ " GROUP BY n.kind, n.actorId, n.href")
	List<Object[]> countUnreadByKindAndActor(@Param("userId") String userId, @Param("context") String context);

	/**
	 * The unread notification of a group, row-locked until the transaction ends: joining it is a
	 * read-then-write of its count, and two messages (or reactions) arriving together must not both
	 * read the same count.
	 */
	@Query(value = "select * from notifications where user_id = :userId and group_key = :groupKey and is_read = false"
		+ " order by created_at desc limit 1 for update", nativeQuery = true)
	java.util.Optional<NotificationJpaEntity> lockUnreadGroup(@Param("userId") String userId,
			@Param("groupKey") String groupKey);

	/** Only the unread rows: rewriting (and locking) every notification the person ever had was the cost. */
	@Modifying
	@Query(value = "UPDATE NotificationJpaEntity n SET n.read = true WHERE n.userId = :userId AND n.read = false")
	@Transactional
	void markAllReadByUserId(@Param("userId") String userId);

	/** One statement: does not rewrite the rest of the row (a grouped notification may be joined meanwhile). */
	@Modifying
	@Query("UPDATE NotificationJpaEntity n SET n.read = true WHERE n.id = :id AND n.read = false")
	@Transactional
	int markReadById(@Param("id") String id);

	@Modifying
	@Query(value = "UPDATE NotificationJpaEntity n SET n.read = true"
		+ " WHERE n.userId = :userId AND n.href = :href AND n.read = false")
	@Transactional
	void markReadByUserIdAndHref(@Param("userId") String userId, @Param("href") String href);

}
