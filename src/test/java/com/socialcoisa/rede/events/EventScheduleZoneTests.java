package com.socialcoisa.rede.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.events.domain.Event;

/**
 * O relógio do evento é o de quem participa, não o de UTC.
 *
 * O caso que originou isto: um evento criado para hoje às 20h aparecia como passado às 17h — a
 * hora digitada virava hora de UTC, e no Brasil (UTC-3) o fim do evento chegava três horas antes.
 * Estes testes travam a tradução data+hora -> instante sem depender do fuso da máquina que roda a
 * suíte (o CI pode estar em UTC).
 */
class EventScheduleZoneTests {

	private static final ZoneId NETWORK = ZoneId.of("America/Sao_Paulo");

	private static Event eventAt(String date, String time) {
		return Event.create("event_tz", "Encontro", date, time, "ONLINE", "Host", null, null, null, "user_1",
			Instant.parse("2026-01-01T00:00:00Z"));
	}

	@Test
	void aHoraDigitadaValeNoRelogioDeQuemParticipa() {
		Event event = eventAt("2026-11-08", "20:00");

		Instant expires = event.expiresAt().orElseThrow();

		// 20:00 em São Paulo é 23:00 UTC — e é assim que o evento é lido em qualquer fuso.
		assertThat(expires).isEqualTo(Instant.parse("2026-11-08T23:00:00Z"));
		assertThat(expires.atZone(NETWORK).getHour()).isEqualTo(20);
	}

	@Test
	void oEventoSoTerminaDepoisDaHoraDele() {
		Event event = eventAt("2026-11-08", "20:00");

		// Às 19h (São Paulo) ainda não terminou — antes da correção, um evento de 20h já estava
		// "passado" a partir das 17h.
		Instant dezenove = ZonedDateTime.of(2026, 11, 8, 19, 0, 0, 0, NETWORK).toInstant();
		assertThat(event.isExpired(dezenove, Duration.ZERO)).isFalse();

		Instant vinteEUm = ZonedDateTime.of(2026, 11, 8, 20, 1, 0, 0, NETWORK).toInstant();
		assertThat(event.isExpired(vinteEUm, Duration.ZERO)).isTrue();
	}

	@Test
	void semHoraOTerminoEDeMeiaNoiteLocal() {
		Event event = eventAt("2026-11-08", null);

		Instant expires = event.expiresAt().orElseThrow();

		assertThat(expires.atZone(NETWORK).getHour()).isEqualTo(23);
		assertThat(expires.atZone(NETWORK).getMinute()).isEqualTo(59);
	}
}
