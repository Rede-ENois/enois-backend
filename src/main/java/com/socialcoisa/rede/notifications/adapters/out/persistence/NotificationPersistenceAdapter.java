package com.socialcoisa.rede.notifications.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.notifications.application.port.out.NotificationRepository;
import com.socialcoisa.rede.notifications.domain.Notification;
import com.socialcoisa.rede.notifications.domain.NotificationContext;

@Repository
class NotificationPersistenceAdapter implements NotificationRepository {

	private final SpringDataNotificationJpaRepository springData;

	NotificationPersistenceAdapter(SpringDataNotificationJpaRepository springData) {
		this.springData = springData;
	}

	@Override
	public void save(Notification notification) {
		springData.save(NotificationJpaEntity.fromDomain(notification));
	}

	@Override
	public Optional<Notification> findById(String id) {
		return springData.findById(id).map(NotificationJpaEntity::toDomain);
	}

	@Override
	public List<Notification> findByUserId(String userId, java.time.Instant before, NotificationContext context,
			int limit) {
		return springData.findByUserId(userId, before, context == null ? null : context.name(), PageRequest.of(0, limit))
			.stream()
			.map(NotificationJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<UnreadCount> countUnreadByKindAndActor(String userId, NotificationContext context) {
		return springData.countUnreadByKindAndActor(userId, context == null ? null : context.name())
			.stream()
			.map(row -> new UnreadCount((String) row[0], (String) row[1], (String) row[2], ((Number) row[3]).longValue()))
			.toList();
	}

	@Override
	public Optional<Notification> findUnreadGroup(String userId, String groupKey) {
		return springData.lockUnreadGroup(userId, groupKey).map(NotificationJpaEntity::toDomain);
	}

	@Override
	public void markRead(String notificationId) {
		springData.markReadById(notificationId);
	}

	@Override
	public void markAllReadByUser(String userId) {
		springData.markAllReadByUserId(userId);
	}

	@Override
	public void markReadByUserAndHref(String userId, String href) {
		springData.markReadByUserIdAndHref(userId, href);
	}

}
