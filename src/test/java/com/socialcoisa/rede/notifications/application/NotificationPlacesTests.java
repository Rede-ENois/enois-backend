package com.socialcoisa.rede.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.notifications.application.port.out.NotificationPlaceRepository;
import com.socialcoisa.rede.notifications.application.port.out.WeeklyDigestRepository;
import com.socialcoisa.rede.notifications.application.port.out.WeeklyDigestRepository.AttendingEvent;
import com.socialcoisa.rede.notifications.domain.NotificationPlace;
import com.socialcoisa.rede.notifications.domain.NotificationTarget;

/** A whole page resolves with one read per kind of target, never one per notification. */
class NotificationPlacesTests {

	private final NotificationPlaceRepository repository = mock(NotificationPlaceRepository.class);
	private final WeeklyDigestRepository digests = mock(WeeklyDigestRepository.class);
	/** 01:30 UTC on Nov 3 is still Nov 2 in Brazil. */
	private final NotificationPlaces places = new NotificationPlaces(repository, digests,
		Clock.fixed(Instant.parse("2026-11-03T01:30:00Z"), ZoneOffset.UTC));

	@Test
	void hrefsAreReadInTheFormatsTheModulesWrite() {
		assertThat(NotificationTarget.of("/comunidades/c1/posts"))
			.contains(new NotificationTarget(NotificationTarget.Type.COMMUNITY, "c1"));
		assertThat(NotificationTarget.of("/api/feed/posts/p1?reacoes=1"))
			.contains(new NotificationTarget(NotificationTarget.Type.POST, "p1"));
		assertThat(NotificationTarget.of("/grupos/roda-de-bh"))
			.contains(new NotificationTarget(NotificationTarget.Type.GROUP, "roda-de-bh"));
		assertThat(NotificationTarget.of("/eventos?evento=e1"))
			.contains(new NotificationTarget(NotificationTarget.Type.EVENT, "e1"));
		assertThat(NotificationTarget.of("/eventos")).isEmpty();
		assertThat(NotificationTarget.of("/vagas/j1")).isEmpty();
		assertThat(NotificationTarget.of(null)).isEmpty();
	}

	@Test
	void aPageOfManyNotificationsIsResolvedWithOneReadPerKind() {
		List<String> hrefs = IntStream.range(0, 40).mapToObj(i -> switch (i % 5) {
			case 0 -> "/comunidades/c" + i;
			case 1 -> "/api/feed/posts/p" + i;
			case 2 -> "/grupos/g" + i;
			case 3 -> "/eventos?evento=e" + i;
			default -> "/suporte";
		}).toList();
		when(repository.communitiesByIds(any())).thenReturn(
			Map.of("c0", new NotificationPlace(NotificationPlace.Type.COMMUNITY, "c0", "Mesa", null)));
		when(repository.eventDatesByIds(any())).thenReturn(Map.of("e3", "2026-11-02"));

		Map<String, NotificationPlaces.Resolved> resolved = places.resolve(hrefs);

		verify(repository, times(1)).communitiesByIds(any());
		verify(repository, times(1)).communitiesOfPosts(any());
		verify(repository, times(1)).groupsBySlugs(any());
		verify(repository, times(1)).eventDatesByIds(any());
		assertThat(resolved).hasSize(33); // 32 distinct targets plus the shared "/suporte"
		assertThat(resolved.get("/comunidades/c0").place().name()).isEqualTo("Mesa");
		assertThat(resolved.get("/comunidades/c5").place()).isNull();
		assertThat(resolved.get("/eventos?evento=e3").eventDate()).isEqualTo(LocalDate.of(2026, 11, 2));
		assertThat(resolved.get("/eventos?evento=e8").eventDate()).isNull();
		assertThat(resolved.get("/suporte")).isEqualTo(new NotificationPlaces.Resolved(null, null));
	}

	@Test
	void eventsTodayCountsConfirmedEventsOfTheBrazilianDay() {
		when(digests.attendingEvents("u1")).thenReturn(List.of(new AttendingEvent("e1", "A", "2026-11-02", "19:00"),
			new AttendingEvent("e2", "B", "02/11/2026", "20:00"), new AttendingEvent("e3", "C", "2026-11-03", "19:00"),
			new AttendingEvent("e4", "D", "sem data", null)));

		assertThat(places.eventsToday("u1")).isEqualTo(2);
		assertThat(places.eventsToday("nobody")).isZero();
	}

}
