package com.socialcoisa.rede.notifications.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.notifications.application.port.out.NotificationRepository;
import com.socialcoisa.rede.notifications.domain.Notification;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class NotificationService {

	private final NotificationRepository repository;
	private final Clock clock;

	public NotificationService(NotificationRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional
	public Notification create(String userId, String kind, String actorId, String title, String body, String href,
			String ctaLabel) {
		String id = "notification_" + UUID.randomUUID().toString().replace("-", "");
		Notification notification = Notification.create(id, userId, kind, actorId, title, body, href, ctaLabel,
			clock.instant());
		repository.save(notification);
		return notification;
	}

	@Transactional
	public Notification markRead(String notificationId, String userId) {
		Notification notification = repository.findById(notificationId)
			.orElseThrow(() -> new ApiException(404, "NOTIFICATION_NOT_FOUND", "Notificacao nao encontrada."));
		if (!notification.userId().equals(userId)) {
			throw new ApiException(403, "FORBIDDEN", "Esta notificacao nao pertence a voce.");
		}
		repository.markRead(notificationId);
		return notification.markRead();
	}

	@Transactional
	public void markAllRead(String userId) {
		repository.markAllReadByUser(userId);
	}

	public List<Notification> list(String userId, int limit) {
		return repository.findByUserId(userId, limit);
	}

}
