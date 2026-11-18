package com.socialcoisa.rede.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarRecurrence;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.ApiException;

class CalendarImportedCountTests {

	private static final Instant AGORA = Instant.parse("2024-01-01T00:00:00Z");

	private final CalendarEntryRepository entries = mock(CalendarEntryRepository.class);
	private final Map<String, CalendarEntry> salvas = new LinkedHashMap<>();
	private AnnotationConfigApplicationContext contexto;
	private CalendarRecurrenceService recurrences;

	@BeforeEach
	void preparar() {
		PlatformTransactionManager transacoes = mock(PlatformTransactionManager.class);
		when(transacoes.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

		contexto = new AnnotationConfigApplicationContext();
		contexto.registerBean(CalendarEntryRepository.class, () -> entries);
		contexto.registerBean(Clock.class, () -> Clock.fixed(AGORA, ZoneOffset.UTC));
		contexto.registerBean(PlatformTransactionManager.class, () -> transacoes);
		contexto.registerBean(CalendarRecurrenceService.class);
		contexto.refresh();
		recurrences = contexto.getBean(CalendarRecurrenceService.class);

		doAnswer(chamada -> {
			CalendarEntry entrada = chamada.getArgument(0);
			salvas.put(entrada.id(), entrada);
			return null;
		}).when(entries).save(any(CalendarEntry.class));
		when(entries.findById(anyString())).thenAnswer(
			chamada -> Optional.ofNullable(salvas.get(chamada.getArgument(0))));
		when(entries.findBySeries(anyString())).thenAnswer(chamada -> serie(chamada.getArgument(0)));
		when(entries.findBySeriesAndStart(anyString(), any(Instant.class))).thenAnswer(chamada -> {
			String serieId = chamada.getArgument(0);
			Instant inicio = chamada.getArgument(1);
			return serie(serieId).stream().filter(entrada -> entrada.startsAt().equals(inicio)).findFirst();
		});
	}

	@AfterEach
	void limpar() {
		contexto.close();
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 3, 18 })
	void contagemFinitaResisteAExtensao(int quantidade) {
		CalendarEntry fundadora = fundadora(Instant.parse("2024-01-02T10:00:00Z"), ZoneOffset.UTC);
		Instant limite = fundadora.startsAt().plus(Duration.ofDays(7L * (quantidade - 1)));
		String serieId = importar(fundadora, CalendarRecurrence.WEEKLY, Set.of(), null, quantidade);

		assertThat(serie(serieId)).allSatisfy(entrada -> assertThat(entrada.seriesUntil()).isEqualTo(limite));
		CalendarEntry ultima = ultima(serieId);
		recurrences.extend(ultima, Instant.parse("2024-03-21T00:00:00Z"));
		recurrences.extend(ultima(serieId), Instant.parse("2024-03-21T00:00:00Z"));

		assertThat(inicios(serieId)).hasSize(quantidade).last().isEqualTo(limite);
		assertThat(serie(serieId)).noneMatch(CalendarEntry::tail);
	}

	@ParameterizedTest
	@ValueSource(strings = { "2023-11-01T10:00:00Z", "2019-01-02T10:00:00Z" })
	void ocorrenciasPassadasConsomemAContagem(String inicio) {
		CalendarEntry fundadora = fundadora(Instant.parse(inicio), ZoneOffset.UTC);

		String serieId = importar(fundadora, CalendarRecurrence.WEEKLY, Set.of(), null, 3);

		assertThat(inicios(serieId)).containsExactly(fundadora.startsAt());
		assertThat(fundadora.seriesUntil()).isEqualTo(fundadora.startsAt().plus(Duration.ofDays(14)));
		assertThat(fundadora.tail()).isFalse();
	}

	@Test
	void exclusaoConsomeSuaPosicaoNaContagem() {
		CalendarEntry fundadora = fundadora(Instant.parse("2024-01-02T10:00:00Z"), ZoneOffset.UTC);
		Instant excluida = Instant.parse("2024-01-09T10:00:00Z");
		Instant limite = Instant.parse("2024-01-23T10:00:00Z");

		String serieId = importar(fundadora, CalendarRecurrence.WEEKLY, Set.of(excluida), null, 4);

		assertThat(inicios(serieId)).containsExactly(fundadora.startsAt(),
			Instant.parse("2024-01-16T10:00:00Z"), limite);
		assertThat(fundadora.seriesUntil()).isEqualTo(limite);
		assertThat(serie(serieId)).noneMatch(CalendarEntry::tail);
	}

	@Test
	void mensalMantemOTrintaEUmComoAncora() {
		CalendarEntry fundadora = fundadora(Instant.parse("2024-01-31T10:00:00Z"), ZoneOffset.UTC);
		Instant limite = Instant.parse("2024-03-31T10:00:00Z");

		String serieId = importar(fundadora, CalendarRecurrence.MONTHLY, Set.of(), null, 3);
		recurrences.extend(ultima(serieId), Instant.parse("2024-02-01T00:00:00Z"));

		assertThat(fundadora.seriesUntil()).isEqualTo(limite);
		assertThat(inicios(serieId)).containsExactly(fundadora.startsAt(),
			Instant.parse("2024-02-29T10:00:00Z"), limite);
	}

	@Test
	void contagemSegueOCadeamentoNaLacunaDoHorarioDeVerao() {
		CalendarEntry fundadora = fundadora(Instant.parse("2024-03-03T07:30:00Z"),
			ZoneId.of("America/New_York"));
		Instant limite = Instant.parse("2024-03-17T07:30:00Z");

		String serieId = importar(fundadora, CalendarRecurrence.WEEKLY, Set.of(), null, 3);

		assertThat(fundadora.seriesUntil()).isEqualTo(limite);
		assertThat(inicios(serieId)).containsExactly(fundadora.startsAt(),
			Instant.parse("2024-03-10T07:30:00Z"), limite);
	}

	@Test
	void untilAnteriorPrevaleceSobreContagemGrande() {
		CalendarEntry fundadora = fundadora(Instant.parse("2024-01-02T10:00:00Z"), ZoneOffset.UTC);
		Instant limite = Instant.parse("2024-01-10T10:00:00Z");

		String serieId = importar(fundadora, CalendarRecurrence.WEEKLY, Set.of(), limite, Integer.MAX_VALUE);

		assertThat(fundadora.seriesUntil()).isEqualTo(limite);
		assertThat(inicios(serieId)).containsExactly(fundadora.startsAt(),
			Instant.parse("2024-01-09T10:00:00Z"));
	}

	@Test
	void contagemForaDoIntervaloDoBancoERecusada() {
		CalendarEntry fundadora = fundadora(Instant.parse("2024-01-02T10:00:00Z"), ZoneOffset.UTC);

		assertThatThrownBy(() -> importar(fundadora, CalendarRecurrence.WEEKLY, Set.of(), null,
			Integer.MAX_VALUE))
			.isInstanceOfSatisfying(ApiException.class,
				erro -> assertThat(erro.code()).isEqualTo("CALENDAR_RECURRENCE_LIMIT_OUT_OF_RANGE"));
		assertThat(fundadora.seriesId()).isNull();
	}

	private CalendarEntry fundadora(Instant inicio, ZoneId fuso) {
		CalendarEntry entrada = CalendarEntry.create("fundadora", "dono", CalendarScope.SOCIAL,
			CalendarKind.MEETING, new CalendarEntrySource(null, null), "Reunião", null,
			inicio, inicio.plus(Duration.ofHours(1)), fuso, false, AGORA);
		salvas.put(entrada.id(), entrada);
		return entrada;
	}

	private String importar(CalendarEntry fundadora, CalendarRecurrence cadencia, Set<Instant> excluidas,
			Instant limite, int quantidade) {
		int ancora = fundadora.startsAt().atZone(fundadora.zone()).getDayOfMonth();
		return recurrences.materializeImported("dono", fundadora.id(), cadencia, excluidas, ancora,
			limite, quantidade).orElseThrow();
	}

	private List<CalendarEntry> serie(String serieId) {
		return salvas.values().stream().filter(entrada -> serieId.equals(entrada.seriesId())).toList();
	}

	private List<Instant> inicios(String serieId) {
		return serie(serieId).stream().map(CalendarEntry::startsAt).sorted().toList();
	}

	private CalendarEntry ultima(String serieId) {
		return serie(serieId).stream()
			.max((primeira, segunda) -> primeira.startsAt().compareTo(segunda.startsAt())).orElseThrow();
	}
}
