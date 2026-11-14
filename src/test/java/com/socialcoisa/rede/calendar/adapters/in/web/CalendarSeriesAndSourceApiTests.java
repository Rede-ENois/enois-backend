package com.socialcoisa.rede.calendar.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarRecurrence;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * O que a superfície diz (e não diz) sobre série e origem.
 *
 * <p>Dois contratos da fase 11: reagendar uma ocorrência para o horário de uma irmã recusa com o
 * código próprio (não é conflito de integridade genérico), e a origem do compromisso (`ICS`,
 * mentoria) é assunto do dono — o convidado recebe os campos nulos.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class CalendarSeriesAndSourceApiTests {

	private static final Instant STARTS_AT = Instant.parse("2026-12-20T22:00:00Z");
	private static final Instant ENDS_AT = Instant.parse("2026-12-20T23:00:00Z");
	private static final Instant CREATED_AT = Instant.parse("2026-11-08T00:00:00Z");
	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired CalendarEntryRepository entries;

	private TestAuthHelper.AuthSession account(String userId) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(userId);
	}

	private String usernameOf(TestAuthHelper.AuthSession session) throws Exception {
		String body = mockMvc.perform(get("/me").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return objectMapper.readTree(body).get("username").asString();
	}

	private String saveSeries(String ownerId, String seriesId, Instant start, boolean tail) {
		String id = "cal_" + UUID.randomUUID().toString().replace("-", "");
		CalendarEntry entry = CalendarEntry.create(id, ownerId, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), "Aula semanal", null, start, start.plus(Duration.ofHours(1)),
			ZONE, false, CREATED_AT);
		entry.joinSeries(seriesId, CalendarRecurrence.WEEKLY, CREATED_AT);
		if (!tail) {
			entry.closeTail();
		}
		entries.save(entry);
		return id;
	}

	private void invite(TestAuthHelper.AuthSession owner, String entryId, String username, String key)
			throws Exception {
		mockMvc
			.perform(post("/calendar/entries/{entryId}/participants", entryId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", username))))
			.andExpect(status().isCreated());
	}

	@Test
	void reschedulingAOccurrenceOntoASiblingSlotNamesTheConflict() throws Exception {
		// Sem a recusa antecipada, o índice único da série estourava no save e a pessoa lia
		// "Este registro ja existe" — sem saber de qual horário batia.
		String owner = account("cal_series_own").userId();
		String seriesId = "cal_series_" + UUID.randomUUID().toString().replace("-", "");
		Instant firstStart = STARTS_AT;
		Instant secondStart = STARTS_AT.plus(Duration.ofDays(7));
		String firstId = saveSeries(owner, seriesId, firstStart, false);
		saveSeries(owner, seriesId, secondStart, true);

		Instant target = secondStart.plus(Duration.ofHours(2));
		// Mudar para um horário livre funciona.
		mockMvc
			.perform(patch("/calendar/entries/{entryId}", firstId).header("X-Session-Token", account("cal_series_own").token())
				.header("Idempotency-Key", "series-resched-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("startsAt", target.toString(),
					"endsAt", target.plus(Duration.ofHours(1)).toString(), "timeZoneId", ZONE.getId()))))
			.andExpect(status().isOk());

		// Mudar para o horário exato da irmã recusa com o código próprio.
		mockMvc
			.perform(patch("/calendar/entries/{entryId}", firstId).header("X-Session-Token", account("cal_series_own").token())
				.header("Idempotency-Key", "series-resched-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("startsAt", secondStart.toString(),
					"endsAt", secondStart.plus(Duration.ofHours(1)).toString(), "timeZoneId", ZONE.getId()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CALENDAR_SERIES_SLOT_TAKEN"));
	}

	@Test
	void aDeclinedGuestDoesNotSeeWhereTheEntryCameFrom() throws Exception {
		// A origem (ICS/UID, id da mentoria) é o bookkeeping com o módulo dono. O convidado —
		// em qualquer estado, recusado inclusive — lê os campos nulos.
		var owner = account("cal_src_owner");
		var guest = account("cal_src_guest");
		String entryId = "cal_" + UUID.randomUUID().toString().replace("-", "");
		entries.save(CalendarEntry.create(entryId, owner.userId(), CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource("ICS", "uid_" + UUID.randomUUID() + "@example.test"), "Evento importado",
			null, STARTS_AT, ENDS_AT, ZONE, false, CREATED_AT));
		invite(owner, entryId, usernameOf(guest), "src-1");
		mockMvc
			.perform(patch("/calendar/entries/{entryId}/participants/me", entryId)
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "src-resp-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("state", "DECLINED"))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/calendar/entries/{entryId}", entryId).header("X-Session-Token", guest.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.owner").value(false))
			.andExpect(jsonPath("$.entry.sourceType").value(nullValue()))
			.andExpect(jsonPath("$.entry.sourceId").value(nullValue()));

		// Quem é dono continua vendo a origem: é a chave de idempotência com o módulo.
		mockMvc.perform(get("/calendar/entries/{entryId}", entryId).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.owner").value(true))
			.andExpect(jsonPath("$.entry.sourceType").value("ICS"));
	}

}
