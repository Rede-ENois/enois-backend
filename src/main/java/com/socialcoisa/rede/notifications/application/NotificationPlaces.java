package com.socialcoisa.rede.notifications.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.notifications.application.port.out.NotificationPlaceRepository;
import com.socialcoisa.rede.notifications.application.port.out.WeeklyDigestRepository;
import com.socialcoisa.rede.notifications.domain.NotificationPlace;
import com.socialcoisa.rede.notifications.domain.NotificationTarget;

/**
 * Read-time enrichment of notifications from their {@code href}: the community or group they came
 * from and the day of the event they are about. Each kind of target is read once for the whole set.
 */
@Service
public class NotificationPlaces {

	/** Event days are calendar days in Brazil, like the rest of the app's "today". */
	static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	private final NotificationPlaceRepository repository;
	private final WeeklyDigestRepository digests;
	private final Clock clock;

	public NotificationPlaces(NotificationPlaceRepository repository, WeeklyDigestRepository digests, Clock clock) {
		this.repository = repository;
		this.digests = digests;
		this.clock = clock;
	}

	/** What one href resolves to; both null when it points at no place or event. */
	public record Resolved(NotificationPlace place, LocalDate eventDate) {

		static final Resolved NONE = new Resolved(null, null);
	}

	/** Resolution by href (hrefs without a known target map to {@link Resolved#NONE}). */
	public Map<String, Resolved> resolve(Collection<String> hrefs) {
		Map<String, NotificationTarget> targets = new HashMap<>();
		for (String href : hrefs) {
			NotificationTarget.of(href).ifPresent(target -> targets.put(href, target));
		}
		Map<String, NotificationPlace> communities = repository.communitiesByIds(keys(targets, NotificationTarget.Type.COMMUNITY));
		Map<String, NotificationPlace> posts = repository.communitiesOfPosts(keys(targets, NotificationTarget.Type.POST));
		Map<String, NotificationPlace> groups = repository.groupsBySlugs(keys(targets, NotificationTarget.Type.GROUP));
		Map<String, String> eventDates = repository.eventDatesByIds(keys(targets, NotificationTarget.Type.EVENT));
		Map<String, Resolved> resolved = new HashMap<>();
		for (String href : hrefs) {
			NotificationTarget target = targets.get(href);
			resolved.put(href, target == null ? Resolved.NONE : switch (target.type()) {
				case COMMUNITY -> new Resolved(communities.get(target.key()), null);
				case POST -> new Resolved(posts.get(target.key()), null);
				case GROUP -> new Resolved(groups.get(target.key()), null);
				case EVENT -> new Resolved(null, Event.parseDate(eventDates.get(target.key())));
			});
		}
		return resolved;
	}

	/** Events happening today (Brazil) whose attendance the person confirmed. */
	public int eventsToday(String userId) {
		LocalDate today = LocalDate.now(clock.withZone(ZONE));
		return (int) digests.attendingEvents(userId).stream()
			.filter(event -> today.equals(Event.parseDate(event.date())))
			.count();
	}

	private static Set<String> keys(Map<String, NotificationTarget> targets, NotificationTarget.Type type) {
		return targets.values().stream()
			.filter(target -> target.type() == type)
			.map(NotificationTarget::key)
			.collect(Collectors.toSet());
	}
}
