package com.socialcoisa.rede.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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

/**
 * As colunas da série no banco de verdade (V277).
 *
 * <p>O que este teste tranca: `series_id`, cadência e marca de ponta voltam iguais, a série é
 * encontrada pela irmã e pelo par (série, início), e a ponta é listada pelo worker — sem depender de
 * a irmã mais recente ser a certa depois de uma ocorrência reagendada ou cancelada. O compromisso
 * único continua com as três colunas nulas.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class CalendarSeriesPersistenceTests {

	private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

	@Autowired
	private CalendarEntryRepository repository;

	private CalendarEntry entry(String owner, Instant start) {
		String id = "cal_" + UUID.randomUUID().toString().replace("-", "");
		return CalendarEntry.create(id, owner, CalendarScope.PROFESSIONAL, CalendarKind.MEETING,
			new CalendarEntrySource(null, null), "Reunião semanal", null, start, start.plus(Duration.ofHours(1)),
			SAO_PAULO, false, NOW);
	}

	@Test
	void storesAndReadsBackTheSeriesColumns() {
		String seriesId = "cal_series_" + UUID.randomUUID().toString().replace("-", "");
		CalendarEntry entry = entry("cal_owner_" + UUID.randomUUID(), NOW.plus(Duration.ofDays(1)));
		entry.joinSeries(seriesId, CalendarRecurrence.BIWEEKLY, NOW);
		repository.save(entry);

		CalendarEntry loaded = repository.findById(entry.id()).orElseThrow();

		assertThat(loaded.seriesId()).isEqualTo(seriesId);
		assertThat(loaded.recurrence()).isEqualTo(CalendarRecurrence.BIWEEKLY);
		assertThat(loaded.tail()).isTrue();
	}

	@Test
	void uniqueEntryKeepsTheSeriesColumnsEmpty() {
		CalendarEntry entry = entry("cal_owner_" + UUID.randomUUID(), NOW.plus(Duration.ofDays(1)));
		repository.save(entry);

		CalendarEntry loaded = repository.findById(entry.id()).orElseThrow();

		assertThat(loaded.seriesId()).isNull();
		assertThat(loaded.recurrence()).isNull();
		assertThat(loaded.tail()).isFalse();
	}

	@Test
	void findsTheSiblingByStartAndTheTailByFlag() {
		String owner = "cal_owner_" + UUID.randomUUID();
		String seriesId = "cal_series_" + UUID.randomUUID().toString().replace("-", "");
		Instant firstStart = NOW.plus(Duration.ofDays(1));
		Instant secondStart = firstStart.plus(Duration.ofDays(7));
		CalendarEntry first = entry(owner, firstStart);
		first.joinSeries(seriesId, CalendarRecurrence.WEEKLY, NOW);
		first.closeTail();
		CalendarEntry second = entry(owner, secondStart);
		second.joinSeriesQuietly(seriesId, CalendarRecurrence.WEEKLY);
		second.openTail();
		repository.save(first);
		repository.save(second);

		assertThat(repository.findBySeries(seriesId)).extracting(CalendarEntry::id)
			.containsExactly(first.id(), second.id());
		assertThat(repository.findBySeriesAndStart(seriesId, secondStart)).map(CalendarEntry::id)
			.contains(second.id());
		assertThat(repository.findBySeriesAndStart(seriesId, secondStart.plus(Duration.ofDays(1)))).isEmpty();
		assertThat(repository.findSeriesTails()).extracting(CalendarEntry::id).contains(second.id());
		assertThat(repository.findSeriesTails()).filteredOn(tail -> tail.seriesId().equals(seriesId)).hasSize(1);
	}
}
