package com.socialcoisa.rede.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.calendar.application.CalendarRecurrenceService;
import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarRecurrence;
import com.socialcoisa.rede.calendar.domain.CalendarScope;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class CalendarExtensaoLimitePersistenciaTests {

	private static final ZoneId BERLIM = ZoneId.of("Europe/Berlin");
	private static final Instant AGORA = Instant.parse("2027-01-01T00:00:00Z");

	@Autowired
	private CalendarEntryRepository entradas;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void persisteFechamentoImediatoAoAlcancarUntilAlemDaJanelaInicial() {
		CalendarRecurrenceService servico = new CalendarRecurrenceService(
			entradas, Clock.fixed(AGORA, BERLIM), false);
		String dono = "cal_lim_ext_dono_" + UUID.randomUUID();
		String id = "cal_lim_ext_" + UUID.randomUUID().toString().replace("-", "");
		Instant limite = inicio(5, 3);
		CalendarEntry fundadora = CalendarEntry.create(id, dono, CalendarScope.PROFESSIONAL,
			CalendarKind.MEETING, new CalendarEntrySource("ICS", id), "Reunião com limite", null,
			inicio(1, 4), inicio(1, 4).plus(Duration.ofHours(1)), BERLIM, false, AGORA);
		entradas.save(fundadora);
		String serie = servico.materializeImported(dono, id, CalendarRecurrence.WEEKLY,
			Set.of(), 4, limite, null).orElseThrow();
		assertThat(limite).isAfter(AGORA.plus(Duration.ofDays(CalendarRecurrence.HORIZON_DAYS)));
		List<CalendarEntry> pontas = pontas(dono, serie);
		assertThat(pontas).hasSize(1);
		CalendarEntry pontaInicial = pontas.getFirst();
		assertThat(pontaInicial.startsAt()).isEqualTo(inicio(3, 29));

		int criadas = servico.extend(pontaInicial, Instant.parse("2027-03-25T00:00:00Z"));

		assertThat(criadas).isEqualTo(5);
		List<CalendarEntry> depois = entradas.findBySeries(serie);
		assertThat(depois).hasSize(18).allSatisfy(entrada -> {
			assertThat(entrada.ownerId()).isEqualTo(dono);
			assertThat(entrada.seriesUntil()).isEqualTo(limite);
			assertThat(entrada.zone()).isEqualTo(BERLIM);
			assertThat(entrada.startsAt()).isBeforeOrEqualTo(limite);
		});
		CalendarEntry ultima = entradas.findBySeriesAndStart(serie, limite).orElseThrow();
		assertThat(ultima.startsAt()).isEqualTo(depois.getLast().startsAt());
		assertThat(jdbc.queryForObject("select tail from calendar_entries where id = ? and owner_id = ? "
			+ "and series_id = ?", Boolean.class, ultima.id(), dono, serie)).isFalse();
		assertThat(entradas.findById(ultima.id()).orElseThrow().tail()).isFalse();
		assertThat(pontas(dono, serie)).isEmpty();
		assertThat(servico.extend(ultima, Instant.parse("2027-03-25T00:00:00Z"))).isZero();
		assertThat(entradas.findBySeries(serie)).extracting(CalendarEntry::id)
			.containsExactlyElementsOf(depois.stream().map(CalendarEntry::id).toList());
		assertThat(pontas(dono, serie)).isEmpty();
	}

	private List<CalendarEntry> pontas(String dono, String serie) {
		return entradas.findSeriesTails().stream()
			.filter(entrada -> dono.equals(entrada.ownerId()) && serie.equals(entrada.seriesId())).toList();
	}

	private static Instant inicio(int mes, int dia) {
		return ZonedDateTime.of(2027, mes, dia, 19, 0, 0, 0, BERLIM).toInstant();
	}
}
