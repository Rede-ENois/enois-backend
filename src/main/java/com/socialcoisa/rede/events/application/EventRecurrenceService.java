package com.socialcoisa.rede.events.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.events.application.port.out.EventRepository;
import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.events.domain.EventRecurrence;
import com.socialcoisa.rede.identity.application.port.out.CredentialRepository;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.security.SuspiciousText;

/**
 * Recurring events ("rituais"): once an occurrence is over, the next one in the series is scheduled
 * and the people who confirmed the previous one are told, so they can confirm again. Attendance is
 * never copied: showing up is always a fresh choice.
 *
 * Only the latest occurrence of a series spawns, and (series_id, date) is unique, so running twice
 * (or on two instances) never creates the same occurrence twice. The series stops (recurrence goes
 * back to NONE) when the host's account is no longer active or the event is flagged by moderation.
 */
@Service
public class EventRecurrenceService {

	private static final Logger LOG = LoggerFactory.getLogger(EventRecurrenceService.class);

	private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private final EventRepository repository;
	private final NotificationService notifications;
	private final CredentialRepository credentials;
	private final SanctionGateReader sanctionGate;
	private final SuspiciousText suspiciousText;
	private final Clock clock;
	private final boolean enabled;

	public EventRecurrenceService(EventRepository repository, NotificationService notifications,
			CredentialRepository credentials, SanctionGateReader sanctionGate, SuspiciousText suspiciousText,
			Clock clock, @Value("${rede.events.recurrence.enabled:true}") boolean enabled) {
		this.repository = repository;
		this.notifications = notifications;
		this.credentials = credentials;
		this.sanctionGate = sanctionGate;
		this.suspiciousText = suspiciousText;
		this.clock = clock;
		this.enabled = enabled;
	}

	@Scheduled(cron = "${rede.events.recurrence.cron:0 20 * * * *}",
		zone = "${rede.events.recurrence.zone:America/Sao_Paulo}")
	public void scheduledSpawn() {
		if (!enabled) {
			return;
		}
		int created = spawnNextOccurrences();
		if (created > 0) {
			LOG.info("event_recurrence spawned={}", created);
		}
	}

	/**
	 * Creates the next occurrence of every series whose latest occurrence is over.
	 *
	 * No transaction around the loop on purpose: each series is saved on its own, so one failure
	 * (a unique-key race with another instance, for example) does not roll back the others.
	 *
	 * @return how many occurrences were created
	 */
	public int spawnNextOccurrences() {
		int created = 0;
		for (Event event : repository.findRecurring()) {
			try {
				if (spawnNext(event)) {
					created++;
				}
			}
			catch (RuntimeException failure) {
				LOG.warn("event_recurrence_failed eventId={} error={}", event.id(), failure.getMessage());
			}
		}
		return created;
	}

	private boolean spawnNext(Event event) {
		Instant now = clock.instant();
		if (event.seriesId() == null || !event.isExpired(now, Duration.ZERO)) {
			return false;
		}
		boolean latest = repository.findLatestInSeries(event.seriesId())
			.map(found -> found.id().equals(event.id()))
			.orElse(false);
		if (!latest) {
			return false;
		}
		if (!hostIsActive(event.createdBy()) || suspiciousText.matches(event.title(), event.host())) {
			repository.updateSeriesRecurrence(event.seriesId(), EventRecurrence.NONE);
			LOG.info("event_recurrence_stopped seriesId={}", event.seriesId());
			return false;
		}
		Optional<LocalDate> current = event.scheduledDate();
		if (current.isEmpty()) {
			return false;
		}
		LocalDate anchor = anchorOf(event);
		// Catch-up: if the scheduler was off for a while, skip to the first date still ahead.
		LocalDate nextDate = current.get();
		Event next;
		do {
			nextDate = event.recurrence().next(anchor, nextDate);
			next = Event.create("event_" + UUID.randomUUID().toString().replace("-", ""), event.title(),
				nextDate.toString(), event.time(), event.format(), event.host(), event.cover(), event.streamUrl(),
				event.location(), event.createdBy(), now);
		}
		while (next.isExpired(now, Duration.ZERO));
		if (repository.existsInSeries(event.seriesId(), next.date())) {
			return false;
		}
		next.changeRecurrence(event.recurrence(), event.seriesId());
		// A proxima ocorrencia acontece no mesmo lugar: sem isto ela sumia do filtro por cidade.
		next.placeAt(event.place());
		repository.save(next);
		notifyAttendees(event, next, nextDate);
		return true;
	}

	/** Date of the occurrence after this one, or empty for a one-off event (shown in the event view). */
	public Optional<LocalDate> nextOccurrenceDate(Event event) {
		if (event.recurrence() == EventRecurrence.NONE) {
			return Optional.empty();
		}
		return event.scheduledDate().map(date -> event.recurrence().next(anchorOf(event), date));
	}

	/**
	 * The series' first occurrence date (monthly math counts from it). Falls back to the event itself
	 * when the first one is gone (deleted or purged by retention).
	 */
	private LocalDate anchorOf(Event event) {
		LocalDate own = event.scheduledDate().orElse(null);
		if (event.recurrence() != EventRecurrence.MONTHLY || event.seriesId() == null
				|| event.seriesId().equals(event.id())) {
			return own;
		}
		return repository.findById(event.seriesId()).flatMap(Event::scheduledDate).orElse(own);
	}

	/** Deleted account (credentials removed) or active sanction: the ritual does not go on without its host. */
	private boolean hostIsActive(String userId) {
		return !sanctionGate.restricted(userId) && credentials.findByUserId(userId).isPresent();
	}

	private void notifyAttendees(Event previous, Event next, LocalDate nextDate) {
		String body = previous.title() + " volta em " + nextDate.format(BR_DATE) + " às " + next.time()
			+ ". Se quiser ir, confirme presença de novo.";
		for (String userId : repository.findAttendeeIds(previous.id())) {
			if (userId.equals(previous.createdBy())) {
				continue;
			}
			notifications.create(userId, "EVENT_NEXT_OCCURRENCE", previous.createdBy(), "Próximo encontro marcado", body,
				"/eventos?evento=" + next.id(), "Ver evento");
		}
	}

}
