package com.socialcoisa.rede.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarRecurrence;
import com.socialcoisa.rede.calendar.domain.CalendarScope;

class CalendarExtensaoLimiteTests {

	private static final ZoneId BERLIM = ZoneId.of("Europe/Berlin");
	private static final Instant AGORA = Instant.parse("2027-01-01T00:00:00Z");
	private static final String DONO = "cal_limite_dono_teste";
	private static final String FUNDADORA = "cal_limite_fundadora_teste";

	private final CalendarEntryRepository entradas = mock(CalendarEntryRepository.class);
	private final Map<String, CalendarEntry> banco = new LinkedHashMap<>();
	private final List<Gravacao> gravacoes = new ArrayList<>();
	private final CalendarRecurrenceService servico = new CalendarRecurrenceService(
		entradas, Clock.fixed(AGORA, BERLIM), false);

	@BeforeEach
	void prepararFronteiraDePersistencia() {
		doAnswer(chamada -> {
			CalendarEntry entrada = chamada.getArgument(0);
			banco.put(entrada.id(), copia(entrada));
			gravacoes.add(new Gravacao(entrada.id(), entrada.startsAt(), entrada.seriesUntil(), entrada.tail()));
			return null;
		}).when(entradas).save(any(CalendarEntry.class));
		when(entradas.findById(anyString())).thenAnswer(chamada ->
			Optional.ofNullable(banco.get(chamada.getArgument(0))).map(CalendarExtensaoLimiteTests::copia));
		when(entradas.findBySeries(anyString())).thenAnswer(chamada ->
			irmaos(chamada.getArgument(0)));
		when(entradas.findBySeriesAndStart(anyString(), any(Instant.class))).thenAnswer(chamada ->
			irmaos(chamada.getArgument(0)).stream()
				.filter(entrada -> entrada.startsAt().equals(chamada.getArgument(1))).findFirst());
	}

	@Test
	void fechaNovaPontaNaMesmaExtensaoQueAlcancaUntil() {
		Instant limite = inicio(5, 3);
		String serie = importar(limite);
		CalendarEntry pontaInicial = ponta(serie);
		assertThat(limite).isAfter(AGORA.plus(Duration.ofDays(CalendarRecurrence.HORIZON_DAYS)));
		assertThat(pontaInicial.startsAt()).isEqualTo(inicio(3, 29));
		gravacoes.clear();

		int criadas = servico.extend(pontaInicial, Instant.parse("2027-03-25T00:00:00Z"));

		assertThat(criadas).isEqualTo(5);
		List<CalendarEntry> depois = irmaos(serie);
		assertThat(depois).hasSize(18).allSatisfy(entrada -> {
			assertThat(entrada.ownerId()).isEqualTo(DONO);
			assertThat(entrada.seriesUntil()).isEqualTo(limite);
			assertThat(entrada.startsAt()).isBeforeOrEqualTo(limite);
		});
		assertThat(depois).last().extracting(CalendarEntry::startsAt).isEqualTo(limite);
		assertThat(gravacoes.getLast()).isEqualTo(new Gravacao(depois.getLast().id(), limite, limite, false));
		assertThat(depois).noneMatch(CalendarEntry::tail);
	}

	@Test
	void mantemPontaQuandoUntilAindaEstaAlemDaJanela() {
		verificarPontaContinua(inicio(6, 7));
	}

	@Test
	void mantemPontaDaSerieSemUntil() {
		verificarPontaContinua(null);
	}

	@Test
	void fechaPontaJaNoLimiteSemCriarEPermaneceIdempotente() {
		Instant limite = inicio(1, 18);
		String serie = importar(limite);
		CalendarEntry ultima = irmaos(serie).getLast();
		assertThat(ultima.startsAt()).isEqualTo(limite);
		// Simula a ponta legada que ainda seria selecionada pelo próximo ciclo.
		ultima.openTail();
		entradas.save(ultima);
		gravacoes.clear();

		assertThat(servico.extend(ultima, AGORA)).isZero();
		assertThat(servico.extend(irmaos(serie).getLast(), AGORA)).isZero();

		assertThat(irmaos(serie)).hasSize(3).noneMatch(CalendarEntry::tail);
		Gravacao fechamento = new Gravacao(ultima.id(), limite, limite, false);
		assertThat(gravacoes).containsExactly(fechamento, fechamento);
	}

	private void verificarPontaContinua(Instant limite) {
		String serie = importar(limite);
		CalendarEntry pontaInicial = ponta(serie);
		gravacoes.clear();

		assertThat(servico.extend(pontaInicial, Instant.parse("2027-01-15T00:00:00Z"))).isEqualTo(2);

		CalendarEntry novaPonta = ponta(serie);
		assertThat(novaPonta.startsAt()).isEqualTo(inicio(4, 12));
		assertThat(irmaos(serie)).hasSize(15);
		assertThat(gravacoes.getLast()).isEqualTo(
			new Gravacao(novaPonta.id(), inicio(4, 12), limite, true));
		assertThat(banco.get(pontaInicial.id()).tail()).isFalse();
	}

	private String importar(Instant limite) {
		CalendarEntry fundadora = CalendarEntry.create(FUNDADORA, DONO, CalendarScope.PROFESSIONAL,
			CalendarKind.MEETING, new CalendarEntrySource("ICS", "cal_limite_uid_teste"),
			"Reunião com limite", null, inicio(1, 4), inicio(1, 4).plus(Duration.ofHours(1)),
			BERLIM, false, AGORA);
		entradas.save(fundadora);
		return servico.materializeImported(DONO, FUNDADORA, CalendarRecurrence.WEEKLY,
			Set.of(), 4, limite, null).orElseThrow();
	}

	private CalendarEntry ponta(String serie) {
		List<CalendarEntry> pontas = irmaos(serie).stream().filter(CalendarEntry::tail).toList();
		assertThat(pontas).hasSize(1);
		return pontas.getFirst();
	}

	private List<CalendarEntry> irmaos(String serie) {
		return banco.values().stream().filter(entrada -> serie.equals(entrada.seriesId()))
			.sorted(Comparator.comparing(CalendarEntry::startsAt)).map(CalendarExtensaoLimiteTests::copia)
			.toList();
	}

	private static Instant inicio(int mes, int dia) {
		return ZonedDateTime.of(2027, mes, dia, 19, 0, 0, 0, BERLIM).toInstant();
	}

	private static CalendarEntry copia(CalendarEntry entrada) {
		return new CalendarEntry(entrada.id(), entrada.ownerId(), entrada.scope(), entrada.kind(),
			entrada.source(), entrada.title(), entrada.description(), entrada.startsAt(), entrada.endsAt(),
			entrada.zone(), entrada.allDay(), entrada.state(), entrada.createdAt(), entrada.updatedAt(),
			entrada.cancelledAt(), entrada.seriesId(), entrada.recurrence(), entrada.tail(), entrada.seriesUntil());
	}

	/** Valores capturados no save, sem referências ao agregado mutável. */
	private record Gravacao(String id, Instant inicio, Instant limite, boolean ponta) {
	}
}
