package com.socialcoisa.rede.notifications.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataNotificationJpaRepository extends JpaRepository<NotificationJpaEntity, String> {

	List<NotificationJpaEntity> findByUserIdOrderByCreatedAtDesc(String userId);

	@Modifying
	@Query("UPDATE NotificationJpaEntity n SET n.read = true WHERE n.userId = :userId")
	void markAllReadByUserId(@Param("userId") String userId);

}
