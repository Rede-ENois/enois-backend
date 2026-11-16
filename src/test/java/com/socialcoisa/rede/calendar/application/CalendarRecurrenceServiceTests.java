package com.socialcoisa.rede.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarRecurrence;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A série materializada contra o banco de verdade (decisão D42).
 *
 * <p>O que este teste tranca: iniciar preenche cadência na fundadora e cria as ocorrências da janela,
 * cada uma um compromisso completo na agenda; a ponta é única e é a mais à frente (é dela que o
 * worker parte); rodar a extensão de novo não duplica nem cresce a série antes de a janela andar; uma
 * ponta vencida avança até o futuro sem recriar o passado; encerrar cancela as futuras, guarda as
 * passadas e devolve o compromisso a único; compromisso de outro módulo, cadência inválida e entrada
 * de outra pessoa recusam com erro próprio.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class CalendarRecurrenceServiceTests {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	private static final Duration WEEK = Duration.ofDays(7);

	/** O banco guarda microssegundos: o instante do fixture tem de perder a fração antes de comparar. */
	private static Instant seconds(Instant instant) {
		return instant.truncatedTo(ChronoUnit.SECONDS);
	}

	@Autowired CalendarRecurrenceService recurrences;
	@Autowired CalendarEntryRepository entries;
	@Autowired Clock clock;

	private String owner() {
		return "cal_owner_" + UUID.randomUUID();
	}

	private String entry(String ownerId, Instant start) {
		String id = "cal_" + UUID.randomUUID().toString().replace("-", "");
		entries.save(CalendarEntry.create(id, ownerId, CalendarScope.PROFESSIONAL, CalendarKind.MEETING,
			new CalendarEntrySource(null, null), "Reunião semanal", null, start, start.plus(Duration.ofHours(1)),
			SAO_PAULO, false, Instant.parse("2026-11-08T00:00:00Z")));
		return id;
	}

	private List<CalendarEntry> siblings(String seriesId) {
		return entries.findBySeries(seriesId);
	}

	private CalendarEntry tailOf(String seriesId) {
		return siblings(seriesId).stream().filter(CalendarEntry::tail).findFirst().orElseThrow();
	}

	/** A cadeia semanal esperada a partir da fundadora, dentro da janela da extensão. */
	private List<Instant> expectedWeeklyChain(Instant founderStart, Instant now) {
		List<Instant> chain = new ArrayList<>();
		Instant windowEnd = now.plus(Duration.ofDays(CalendarRecurrence.HORIZON_DAYS));
		Instant next = founderStart.plus(WEEK);
		while (!next.isAfter(windowEnd)) {
			chain.add(next);
			next = next.plus(WEEK);
		}
		return chain;
	}

	@Test
	void startFillsTheFounderAndMaterializesTheWindow() {
		String ownerId = owner();
		Instant now = seconds(clock.instant());
		Instant first = now.plus(Duration.ofDays(1));
		String entryId = entry(ownerId, first);

		CalendarEntry founder = recurrences.start(ownerId, entryId, "WEEKLY");

		assertThat(founder.seriesId()).isNotBlank();
		assertThat(founder.recurrence()).isEqualTo(CalendarRecurrence.WEEKLY);
		List<Instant> expected = expectedWeeklyChain(first, now);
		assertThat(expected).isNotEmpty();
		assertThat(siblings(founder.seriesId()).stream().map(CalendarEntry::startsAt).sorted().toList())
			.containsExactlyElementsOf(withFounder(first, expected));
		assertThat(siblings(founder.seriesId())).filteredOn(CalendarEntry::tail).hasSize(1);
		assertThat(tailOf(founder.seriesId()).startsAt()).isEqualTo(expected.get(expected.size() - 1));
	}

	private List<Instant> withFounder(Instant founderStart, List<Instant> chain) {
		List<Instant> all = new ArrayList<>();
		all.add(founderStart);
		all.addAll(chain);
		return all;
	}

	@Test
	void extendingAgainCreatesNothingNew() {
		String ownerId = owner();
		String entryId = entry(ownerId, seconds(clock.instant()).plus(Duration.ofDays(1)));
		String seriesId = recurrences.start(ownerId, entryId, "WEEKLY").seriesId();
		int sizeAfterStart = siblings(seriesId).size();

		int created = recurrences.extend(tailOf(seriesId), seconds(clock.instant()));

		assertThat(created).isZero();
		assertThat(siblings(seriesId)).hasSize(sizeAfterStart);
		assertThat(tailOf(seriesId).startsAt()).isEqualTo(
			siblings(seriesId).stream().map(CalendarEntry::startsAt).max(Instant::compareTo).orElseThrow());
	}

	@Test
	void tailInThePastCatchesUpWithoutCreatingThePast() {
		String ownerId = owner();
		Instant now = seconds(clock.instant());
		Instant first = now.minus(Duration.ofDays(21));
		String entryId = entry(ownerId, first);
		String seriesId = "cal_series_" + UUID.randomUUID().toString().replace("-", "");
		CalendarEntry founder = entries.findById(entryId).orElseThrow();
		founder.joinSeries(seriesId, CalendarRecurrence.WEEKLY, first.minus(WEEK));
		entries.save(founder);

		int created = recurrences.extend(founder, now);

		List<Instant> starts = siblings(seriesId).stream().map(CalendarEntry::startsAt).sorted().toList();
		// Nada do passado é recriado: toda irmã nova nasceu depois de agora...
		assertThat(starts).first().isEqualTo(first);
		assertThat(starts.stream().skip(1)).allMatch(start -> start.isAfter(now));
		// ...e na cadeia certa: cada passo é exatamente sete dias desde a fundadora, sem desvio.
		assertThat(starts.stream().skip(1))
			.allMatch(start -> Duration.between(first, start).toDays() % 7 == 0);
		assertThat(starts).allMatch(start -> !start.isAfter(
			now.plus(Duration.ofDays(CalendarRecurrence.HORIZON_DAYS))));
		assertThat(created).isEqualTo(starts.size() - 1);
		assertThat(tailOf(seriesId).startsAt()).isEqualTo(starts.get(starts.size() - 1));
	}

	@Test
	void stopCancelsTheFutureKeepsThePastAndMakesTheEntrySingleAgain() {
		String ownerId = owner();
		Instant now = clock.instant();
		String entryId = entry(ownerId, now.minus(Duration.ofDays(7)));
		String seriesId = recurrences.start(ownerId, entryId, "WEEKLY").seriesId();

		CalendarEntry after = recurrences.stop(ownerId, entryId);

		assertThat(after.seriesId()).isNull();
		assertThat(after.recurrence()).isNull();
		List<CalendarEntry> series = siblings(seriesId);
		assertThat(series).noneMatch(CalendarEntry::tail);
		assertThat(series.stream().filter(sibling -> sibling.startsAt().isAfter(now)))
			.allMatch(sibling -> !sibling.active());
		assertThat(series.stream().filter(sibling -> !sibling.startsAt().isAfter(now)))
			.allMatch(CalendarEntry::active);
	}

	@Test
	void sourcedEntryRefusesToFoundASeries() {
		String ownerId = owner();
		String id = "cal_" + UUID.randomUUID().toString().replace("-", "");
		Instant start = seconds(clock.instant()).plus(Duration.ofDays(1));
		entries.save(CalendarEntry.create(id, ownerId, CalendarScope.PROFESSIONAL, CalendarKind.MENTORING,
			new CalendarEntrySource("MENTORING", "ms_" + UUID.randomUUID()), "Mentoria: Java", null, start,
			start.plus(Duration.ofHours(1)), SAO_PAULO, false, Instant.parse("2026-11-08T00:00:00Z")));

		assertThatThrownBy(() -> recurrences.start(ownerId, id, "WEEKLY"))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("origem");
	}

	@Test
	void unknownCadenceIsRejectedWithItsOwnError() {
		String ownerId = owner();
		String entryId = entry(ownerId, seconds(clock.instant()).plus(Duration.ofDays(1)));

		assertThatThrownBy(() -> recurrences.start(ownerId, entryId, "DAILY"))
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> recurrences.start(ownerId, entryId, "NONE"))
			.isInstanceOf(ApiException.class);
	}

	@Test
	void anotherOwnerEntryIsNotFound() {
		String ownerId = owner();
		String entryId = entry(ownerId, seconds(clock.instant()).plus(Duration.ofDays(1)));

		assertThatThrownBy(() -> recurrences.start(owner() + "_outro", entryId, "WEEKLY"))
			.isInstanceOf(ApiException.class);
	}
}
