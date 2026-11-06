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

	/** Unread notifications per kind, actor and href (a null context keeps every kind). */
	List<UnreadCount> countUnreadByKindAndActor(String userId,
			com.socialcoisa.rede.notifications.domain.NotificationContext context);

	record UnreadCount(String kind, String actorId, String href, long count) {
	}

	/** The unread notification of a group, if there is one, locked for the caller to join it. */
	Optional<Notification> findUnreadGroup(String userId, String groupKey);

	void markRead(String notificationId);

	void markAllReadByUser(String userId);

	/** Marks read the unread notifications of the person that point at {@code href}. */
	void markReadByUserAndHref(String userId, String href);

}
