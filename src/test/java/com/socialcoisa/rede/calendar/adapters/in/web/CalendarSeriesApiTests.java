package com.socialcoisa.rede.calendar.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * As rotas de série contra o contrato da API: começar e encerrar uma série de compromissos.
 *
 * <p>O que este teste tranca: escrita exige sessão e `Idempotency-Key` (a mesma chave repetida
 * devolve a resposta guardada, não uma segunda série); cadência inválida é erro próprio; a resposta
 * traz `seriesId` e `recurrence`; encerrar devolve o compromisso a compromisso único; entrada com
 * origem recusa; entrada de outra pessoa é indistinguível de uma que não existe.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class CalendarSeriesApiTests {

	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired CalendarEntryRepository entries;
	@Autowired Clock clock;

	private TestAuthHelper.AuthSession account(String userId) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(userId);
	}

	private String saveEntry(String ownerId) {
		String id = "cal_" + UUID.randomUUID().toString().replace("-", "");
		Instant start = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofDays(1));
		entries.save(CalendarEntry.create(id, ownerId, CalendarScope.PROFESSIONAL, CalendarKind.MEETING,
			new CalendarEntrySource(null, null), "Reunião semanal", null, start, start.plus(Duration.ofHours(1)),
			ZONE, false, Instant.now(clock)));
		return id;
	}

	private String saveSourcedEntry(String ownerId) {
		String id = "cal_" + UUID.randomUUID().toString().replace("-", "");
		Instant start = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofDays(1));
		entries.save(CalendarEntry.create(id, ownerId, CalendarScope.PROFESSIONAL, CalendarKind.MENTORING,
			new CalendarEntrySource("MENTORING", "ms_" + UUID.randomUUID()), "Mentoria: Java", null, start,
			start.plus(Duration.ofHours(1)), ZONE, false, Instant.now(clock)));
		return id;
	}

	private String startSeries(String token, String key, String entryId, String recurrence) throws Exception {
		return mockMvc
			.perform(post("/calendar/entries/" + entryId + "/series").header("X-Session-Token", token)
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("recurrence", recurrence))))
			.andReturn().getResponse().getContentAsString();
	}

	@Test
	void startsASeriesAndReportsItInTheResponse() throws Exception {
		var session = account("ser_api_" + UUID.randomUUID());
		String entryId = saveEntry(session.userId());

		mockMvc.perform(post("/calendar/entries/" + entryId + "/series").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "ser-start-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("recurrence", "WEEKLY"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id", notNullValue()))
			.andExpect(jsonPath("$.seriesId", notNullValue()))
			.andExpect(jsonPath("$.recurrence").value("WEEKLY"));
	}

	@Test
	void repeatedKeyReturnsTheSameAnswerInsteadOfRestarting() throws Exception {
		var session = account("ser_api_" + UUID.randomUUID());
		String entryId = saveEntry(session.userId());
		String key = "ser-start-" + UUID.randomUUID();

		String first = startSeries(session.token(), key, entryId, "WEEKLY");
		String second = startSeries(session.token(), key, entryId, "WEEKLY");

		assertThat(second).isEqualTo(first);
	}

	@Test
	void unknownCadenceIsItsOwnError() throws Exception {
		var session = account("ser_api_" + UUID.randomUUID());
		String entryId = saveEntry(session.userId());

		mockMvc.perform(post("/calendar/entries/" + entryId + "/series").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "ser-bad-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("recurrence", "DAILY"))))
			.andExpect(status().isBadRequest());
	}

	@Test
	void sourcedEntryRefuses() throws Exception {
		var session = account("ser_api_" + UUID.randomUUID());
		String entryId = saveSourcedEntry(session.userId());

		mockMvc.perform(post("/calendar/entries/" + entryId + "/series").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "ser-src-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("recurrence", "WEEKLY"))))
			.andExpect(status().isConflict());
	}

	@Test
	void anotherOwnerEntryIsNotFound() throws Exception {
		var session = account("ser_api_" + UUID.randomUUID());
		String entryId = saveEntry("ser_owner_" + UUID.randomUUID());

		mockMvc.perform(post("/calendar/entries/" + entryId + "/series").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "ser-oth-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("recurrence", "WEEKLY"))))
			.andExpect(status().isNotFound());
	}

	@Test
	void stopReturnsTheEntryToSingle() throws Exception {
		var session = account("ser_api_" + UUID.randomUUID());
		String entryId = saveEntry(session.userId());
		String body = startSeries(session.token(), "ser-start-" + UUID.randomUUID(), entryId, "WEEKLY");
		String seriesId = objectMapper.readTree(body).get("seriesId").asString();

		mockMvc.perform(delete("/calendar/entries/" + entryId + "/series").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "ser-stop-" + UUID.randomUUID()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.seriesId").doesNotExist())
			.andExpect(jsonPath("$.recurrence").doesNotExist());

		assertThat(entries.findById(entryId).orElseThrow().seriesId()).isNull();
		assertThat(entries.findBySeries(seriesId)).noneMatch(CalendarEntry::tail);
	}

	@Test
	void seriesWithoutSessionIsUnauthorized() throws Exception {
		String entryId = saveEntry("ser_owner_" + UUID.randomUUID());

		mockMvc.perform(post("/calendar/entries/" + entryId + "/series")
				.header("Idempotency-Key", "ser-no-session").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("recurrence", "WEEKLY"))))
			.andExpect(status().isUnauthorized());
	}
}
