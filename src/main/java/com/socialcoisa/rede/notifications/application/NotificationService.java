package com.socialcoisa.rede.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.notifications.application.port.out.NotificationRepository;
import com.socialcoisa.rede.notifications.domain.Notification;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class NotificationService {

	private final NotificationRepository repository;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	public NotificationService(NotificationRepository repository, ApplicationEventPublisher events, Clock clock) {
		this.repository = repository;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public Notification create(String userId, String kind, String actorId, String title, String body, String href,
			String ctaLabel) {
		String id = "notification_" + UUID.randomUUID().toString().replace("-", "");
		Notification notification = Notification.create(id, userId, kind, actorId, title, body, href, ctaLabel,
			clock.instant());
		repository.save(notification);
		// Quem entrega fora do app (push no celular) escuta este evento depois do commit.
		events.publishEvent(new NotificationCreatedEvent(userId, kind, title, body, href));
		return notification;
	}

	/**
	 * A notification that groups with the unread one of the same target: the first person creates it
	 * (and the push goes out once); everyone after joins it, with {@code groupedBody} writing the text
	 * for the new count and {@code groupedHref} the destination once it covers several people (e.g. the
	 * post with the list of who reacted open). Nothing is pushed for those, so 15 reactions are one
	 * notification and one push.
	 */
	@Transactional
	public Notification createGrouped(String userId, String kind, String groupKey, String actorId, String title,
			String body, java.util.function.IntFunction<String> groupedBody, String href, String groupedHref,
			String ctaLabel) {
		var unread = repository.findUnreadGroup(userId, groupKey);
		if (unread.isPresent()) {
			Notification current = unread.get();
			int count = actorId.equals(current.actorId()) ? current.actorCount() : current.actorCount() + 1;
			Notification joined = current.joinedBy(actorId, count == 1 ? body : groupedBody.apply(count),
				count == 1 ? href : groupedHref, clock.instant());
			repository.save(joined);
			return joined;
		}
		String id = "notification_" + UUID.randomUUID().toString().replace("-", "");
		Notification notification = Notification.grouped(id, userId, kind, groupKey, actorId, title, body, href,
			ctaLabel, clock.instant());
		repository.save(notification);
		events.publishEvent(new NotificationCreatedEvent(userId, kind, title, body, href));
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

	public List<Notification> list(String userId, java.time.Instant before, int limit) {
		return list(userId, before, null, limit);
	}

	/** Notifications of one interface (plus the account-wide ones); a null context keeps all. */
	public List<Notification> list(String userId, java.time.Instant before,
			com.socialcoisa.rede.notifications.domain.NotificationContext context, int limit) {
		return repository.findByUserId(userId, before, context, limit);
	}

}
