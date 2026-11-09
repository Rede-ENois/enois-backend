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
 * A data/hora do evento e texto livre e {@code Event.expiresAt()} a interpreta no fuso da rede
 * (America/Sao_Paulo) — antes era UTC, e o teste ponta a ponta montava o cenario com
 * {@code LocalDate.now()} (fuso local, UTC-3): depois das 21h locais o UTC ja virou o dia, a folga
 * de 24h vencia e o evento era sanitizado. Verde de manha, vermelho de noite. Aqui o instante e
 * explicito, entao o contrato fica pinado: 00:01 do evento vale 03:01 em UTC.
 */
class EventSanitizationWindowTests {

	/** Evento que terminou as 00:01 no fuso da rede (= 03:01 UTC). */
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
		// Terminou 00:01 (= 03:01 UTC); as 02:30 UTC do MESMO dia ainda esta dentro das 24h de folga.
		EventService service = serviceAt("2026-10-15T02:30:00Z");

		assertThat(service.isReadyForSanitization(eventEndingAt("2026-10-15"))).isFalse();
	}

	@Test
	void sanitizesOnceTheGraceWindowHasPassed() {
		// 04:05 UTC do dia seguinte (= 01:05 no fuso da rede): a folga venceu as 03:01 UTC.
		EventService service = serviceAt("2026-10-16T04:05:00Z");

		assertThat(service.isReadyForSanitization(eventEndingAt("2026-10-15"))).isTrue();
	}

	@Test
	void theBoundaryItselfIsStillProtected() {
		// No instante exato do fim da folga nada e apagado: a comparacao e "estritamente depois".
		EventService service = serviceAt("2026-10-16T03:01:00Z");

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
