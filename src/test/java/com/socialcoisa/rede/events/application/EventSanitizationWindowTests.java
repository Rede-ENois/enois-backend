package com.socialcoisa.rede.events.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.events.application.port.out.EventRepository;
import com.socialcoisa.rede.events.domain.Event;

/**
 * A janela de folga de 24h antes de a sanitizacao apagar as presencas, com relogio fixo.
 *
 * Existe por causa de um CI vermelho sem causa no codigo: a data do evento e texto livre e o
 * {@code Event.expiresAt()} a interpreta em UTC, enquanto o teste ponta a ponta montava o cenario
 * com {@code LocalDate.now()} (fuso local, UTC-3). Depois das 21h locais o UTC ja virou o dia, a
 * folga de 24h vencia e o evento era sanitizado — a asserta via 0 presencas onde esperava 1.
 * Verde de manha, vermelho de noite. Aqui o instante e explicito, entao o contrato fica pinado.
 */
class EventSanitizationWindowTests {

	/** Evento que terminou as 00:01 UTC do dia informado. */
	private Event eventEndingAt(String date) {
		return Event.create("e1", "Meetup", date, "00:01", "ONLINE", "Dona", null, null, null, "user",
			Instant.parse("2026-10-01T00:00:00Z"));
	}

	private EventService serviceAt(String instant) {
		var sanctions = mock(com.socialcoisa.rede.shared.sanctions.SanctionGateReader.class);
		org.mockito.Mockito.when(sanctions.banned(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
		return new EventService(mock(EventRepository.class), sanctions,
			new com.socialcoisa.rede.shared.security.SuspiciousText("probe"),
			Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
			mock(com.socialcoisa.rede.shared.application.CacheInvalidator.class));
	}

	@Test
	void keepsTheAttendanceWhileInsideTheGraceWindow() {
		// Terminou 00:01 UTC; as 23:30 UTC do MESMO dia ainda esta dentro das 24h de folga.
		EventService service = serviceAt("2026-10-15T23:30:00Z");

		assertThat(service.isReadyForSanitization(eventEndingAt("2026-10-15"))).isFalse();
	}

	@Test
	void sanitizesOnceTheGraceWindowHasPassed() {
		// 01:05 UTC do dia seguinte: a folga venceu as 00:01. Era exatamente esta a janela que fazia
		// o teste ponta a ponta falhar quando rodava depois das 21h no fuso local.
		EventService service = serviceAt("2026-10-16T01:05:00Z");

		assertThat(service.isReadyForSanitization(eventEndingAt("2026-10-15"))).isTrue();
	}

	@Test
	void theBoundaryItselfIsStillProtected() {
		// No instante exato do fim da folga nada e apagado: a comparacao e "estritamente depois".
		EventService service = serviceAt("2026-10-16T00:01:00Z");

		assertThat(service.isReadyForSanitization(eventEndingAt("2026-10-15"))).isFalse();
	}

	@Test
	void neverSanitizesAnEventWithoutAUsableDate() {
		EventService service = serviceAt("2030-01-01T00:00:00Z");
		Event undated = Event.create("e2", "Meetup", "amanhã", "", "ONLINE", "Dona", null, null, null, "user",
			Instant.parse("2026-10-01T00:00:00Z"));

		assertThat(service.isReadyForSanitization(undated)).isFalse();
	}
}
