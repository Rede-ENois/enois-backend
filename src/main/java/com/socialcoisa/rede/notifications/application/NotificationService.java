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
import com.socialcoisa.rede.notifications.domain.NotificationModule;
import com.socialcoisa.rede.notifications.domain.NotificationPlace;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class NotificationService {

	private final NotificationRepository repository;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	private final NotificationPlaces places;

	public NotificationService(NotificationRepository repository, ApplicationEventPublisher events, Clock clock,
			NotificationPlaces places) {
		this.repository = repository;
		this.events = events;
		this.clock = clock;
		this.places = places;
	}

	@Transactional
	public Notification create(String userId, String kind, String actorId, String title, String body, String href,
			String ctaLabel) {
		return create(userId, kind, actorId, title, body, href, ctaLabel, null);
	}

	/**
	 * Como {@link #create}, com {@code preview}: o trecho do post/comentário de origem que a lista
	 * mostra abaixo do corpo (issue #392). Nulo nas notificações que não têm conteúdo para citar.
	 */
	@Transactional
	public Notification create(String userId, String kind, String actorId, String title, String body, String href,
			String ctaLabel, String preview) {
		String id = "notification_" + UUID.randomUUID().toString().replace("-", "");
		Notification notification = Notification.create(id, userId, kind, actorId, title, body, href, ctaLabel,
			preview, clock.instant());
		repository.save(notification);
		// Quem entrega fora do app (push no celular) escuta este evento depois do commit.
		events.publishEvent(new NotificationCreatedEvent(id, userId, kind, title, body, href));
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
		return createGrouped(userId, kind, groupKey, actorId, title, body, groupedBody, href, groupedHref, ctaLabel,
			null);
	}

	/** Como {@link #createGrouped}, com o trecho do conteúdo de origem (issue #392). */
	@Transactional
	public Notification createGrouped(String userId, String kind, String groupKey, String actorId, String title,
			String body, java.util.function.IntFunction<String> groupedBody, String href, String groupedHref,
			String ctaLabel, String preview) {
		var unread = repository.findUnreadGroup(userId, groupKey);
		if (unread.isPresent()) {
			Notification current = unread.get();
			int count = actorId.equals(current.actorId()) ? current.actorCount() : current.actorCount() + 1;
			Notification joined = current.joinedBy(actorId, count, count == 1 ? body : groupedBody.apply(count),
				count == 1 ? href : groupedHref, clock.instant());
			repository.save(joined);
			return joined;
		}
		return createFirstOfGroup(userId, kind, groupKey, actorId, title, body, href, ctaLabel, null, preview);
	}

	/**
	 * Like {@link #createGrouped}, but every call counts, not every new person: the unread
	 * notification of {@code groupKey} becomes "3 novas publicações em X" ({@code countedBody} of the
	 * new count) and moves back to the top, without a new push. Once read, the next call starts over.
	 * {@code actorCount} then holds the number of events.
	 */
	@Transactional
	public Notification createCounted(String userId, String kind, String groupKey, String actorId, String title,
			String body, java.util.function.IntFunction<String> countedBody, String href, String ctaLabel) {
		var unread = repository.findUnreadGroup(userId, groupKey);
		if (unread.isPresent()) {
			Notification current = unread.get();
			int count = current.actorCount() + 1;
			Notification joined = current.joinedBy(actorId, count, countedBody.apply(count), href, clock.instant());
			repository.save(joined);
			return joined;
		}
		return createFirstOfGroup(userId, kind, groupKey, actorId, title, body, href, ctaLabel);
	}

	/**
	 * Like {@link #createCounted}, but every call is pushed, tagged with {@code groupKey}: the unread
	 * notification of the group counts the events, and each push replaces the previous one on the
	 * device instead of piling up (the messages of one conversation). Once read, the next call starts
	 * over.
	 */
	@Transactional
	public Notification createCountedAndPushed(String userId, String kind, String groupKey, String actorId,
			String title, String body, java.util.function.IntFunction<String> countedBody, String href,
			String ctaLabel) {
		var unread = repository.findUnreadGroup(userId, groupKey);
		if (unread.isPresent()) {
			Notification current = unread.get();
			int count = current.actorCount() + 1;
			String joinedBody = countedBody.apply(count);
			Notification joined = current.joinedBy(actorId, count, joinedBody, href, clock.instant());
			repository.save(joined);
			events.publishEvent(new NotificationCreatedEvent(current.id(), userId, kind, title, joinedBody, href,
				groupKey));
			return joined;
		}
		return createFirstOfGroup(userId, kind, groupKey, actorId, title, body, href, ctaLabel, groupKey);
	}

	private Notification createFirstOfGroup(String userId, String kind, String groupKey, String actorId,
			String title, String body, String href, String ctaLabel) {
		return createFirstOfGroup(userId, kind, groupKey, actorId, title, body, href, ctaLabel, null);
	}

	private Notification createFirstOfGroup(String userId, String kind, String groupKey, String actorId,
			String title, String body, String href, String ctaLabel, String pushTag) {
		return createFirstOfGroup(userId, kind, groupKey, actorId, title, body, href, ctaLabel, pushTag, null);
	}

	private Notification createFirstOfGroup(String userId, String kind, String groupKey, String actorId,
			String title, String body, String href, String ctaLabel, String pushTag, String preview) {
		String id = "notification_" + UUID.randomUUID().toString().replace("-", "");
		Notification notification = Notification.grouped(id, userId, kind, groupKey, actorId, title, body, href,
			ctaLabel, preview, clock.instant());
		repository.save(notification);
		events.publishEvent(new NotificationCreatedEvent(id, userId, kind, title, body, href, pushTag));
		return notification;
	}

	/** Marks read every unread notification of the person that points at {@code href}. */
	@Transactional
	public void markReadByHref(String userId, String href) {
		repository.markReadByUserAndHref(userId, href);
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

	/**
	 * Unread notifications per interface area and per community, for the menu indicators, plus the
	 * confirmed events happening today. Notifications from {@code hiddenActorIds} (blocked either
	 * way) are left out, as in the list. Events are social, so the professional context has none.
	 */
	public UnreadSummary unreadSummary(String userId,
			com.socialcoisa.rede.notifications.domain.NotificationContext context,
			java.util.Set<String> hiddenActorIds) {
		java.util.Map<NotificationModule, Integer> byModule = new java.util.EnumMap<>(NotificationModule.class);
		java.util.Map<String, Integer> countByHref = new java.util.HashMap<>();
		int total = 0;
		for (var row : repository.countUnreadByKindAndActor(userId, context)) {
			if (row.actorId() != null && hiddenActorIds.contains(row.actorId())) {
				continue;
			}
			if (hasOwnChannel(row.kind())) {
				continue;
			}
			int count = Math.toIntExact(row.count());
			byModule.merge(NotificationModule.of(row.kind()), count, Integer::sum);
			if (row.href() != null) {
				countByHref.merge(row.href(), count, Integer::sum);
			}
			total += count;
		}
		java.util.Map<String, Integer> byCommunity = new java.util.TreeMap<>();
		places.resolve(countByHref.keySet()).forEach((href, resolved) -> {
			if (resolved.place() != null && resolved.place().type() == NotificationPlace.Type.COMMUNITY) {
				byCommunity.merge(resolved.place().id(), countByHref.get(href), Integer::sum);
			}
		});
		int eventsToday = context == com.socialcoisa.rede.notifications.domain.NotificationContext.PROFESSIONAL ? 0
			: places.eventsToday(userId);
		return new UnreadSummary(total, byModule, byCommunity, eventsToday);
	}

	/**
	 * Messages and connection requests have their own unread signals (chat balloon, Rede pill) and
	 * the notices panel leaves them out, so counting them here lit "Avisos" with nothing to read.
	 */
	private static boolean hasOwnChannel(String kind) {
		return NotificationModule.of(kind) == NotificationModule.MESSAGES
			|| "CONNECTION_REQUEST".equalsIgnoreCase(kind);
	}

	/** {@code byCommunity}: unread count per community id (only communities with some). */
	public record UnreadSummary(int total, java.util.Map<NotificationModule, Integer> byModule,
			java.util.Map<String, Integer> byCommunity, int eventsToday) {
	}
}
