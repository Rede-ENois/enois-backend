package com.socialcoisa.rede.notifications.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.notifications.domain.Notification;

public interface NotificationRepository {

	void save(Notification notification);

	Optional<Notification> findById(String id);

	/** A null context keeps every kind. */
	List<Notification> findByUserId(String userId, java.time.Instant before,
			com.socialcoisa.rede.notifications.domain.NotificationContext context, int limit);

	/** The unread notification of a group, if there is one. */
	Optional<Notification> findUnreadGroup(String userId, String groupKey);

	void markRead(String notificationId);

	void markAllReadByUser(String userId);

}
