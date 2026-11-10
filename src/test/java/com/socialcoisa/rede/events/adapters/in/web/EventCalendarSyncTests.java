package com.socialcoisa.rede.events.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Confirmar presenca num evento poe o compromisso na agenda de quem vai (fase 4).
 *
 * <p>O evento e a origem (D12): a entrada nasce com `sourceType=EVENT` e o dono e quem confirmou,
 * nao quem criou. Editar o evento reagenda a entrada de todo mundo que ja confirmou; sair do evento
 * remove a entrada, porque o evento continua existindo e nao houve cancelamento a publicar.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventCalendarSyncTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private CalendarEntryRepository entries;

	@Test
	void attendingPutsTheEventOnTheAttendeesCalendar() throws Exception {
		var host = new TestAuthHelper(mockMvc, objectMapper).authenticate("evcal_host1");
		String eventId = create(host, "Encontro de sábado", "2030-08-10", "19:00");

		var guest = new TestAuthHelper(mockMvc, objectMapper).authenticate("evcal_guest1");
		attend(guest, eventId);

		CalendarEntry entry = entries.findBySource(guest.userId(), new CalendarEntrySource("EVENT", eventId))
			.orElseThrow();
		assertThat(entry.active()).isTrue();
		assertThat(entry.title()).isEqualTo("Encontro de sábado");
		assertThat(entry.scope().name()).isEqualTo("SOCIAL");
		assertThat(entry.kind().name()).isEqualTo("EVENT");
		// 19h em São Paulo é 22h UTC.
		assertThat(entry.startsAt()).isEqualTo(Instant.parse("2030-08-10T22:00:00Z"));

		JsonNode agenda = agenda(guest);
		assertThat(agenda.size()).isEqualTo(1);
		assertThat(agenda.get(0).get("entry").get("sourceType").asText()).isEqualTo("EVENT");
		assertThat(agenda.get(0).get("owner").asBoolean()).isTrue();

		// Criar o evento não coloca nada na agenda de quem criou: ele organiza, não comparece.
		assertThat(agenda(host).size()).isZero();
	}

	@Test
	void editingTheEventMovesTheAttendeesEntry() throws Exception {
		var host = new TestAuthHelper(mockMvc, objectMapper).authenticate("evcal_host2");
		String eventId = create(host, "Roda de conversa", "2030-08-11", "18:00");

		var guest = new TestAuthHelper(mockMvc, objectMapper).authenticate("evcal_guest2");
		attend(guest, eventId);

		mockMvc.perform(patch("/events/" + eventId)
				.header("X-Session-Token", host.token())
				.header("Idempotency-Key", "evcal-edit-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Roda de conversa (nova data)",
					"date", "2030-08-12", "time", "20:00", "format", "Online", "host", "Rede"))))
			.andExpect(status().isOk());

		CalendarEntry entry = entries.findBySource(guest.userId(), new CalendarEntrySource("EVENT", eventId))
			.orElseThrow();
		assertThat(entry.title()).isEqualTo("Roda de conversa (nova data)");
		assertThat(entry.startsAt()).isEqualTo(Instant.parse("2030-08-12T23:00:00Z"));
	}

	@Test
	void leavingTheEventRemovesTheEntry() throws Exception {
		var host = new TestAuthHelper(mockMvc, objectMapper).authenticate("evcal_host3");
		String eventId = create(host, "Café com código", "2030-08-13", "09:00");

		var guest = new TestAuthHelper(mockMvc, objectMapper).authenticate("evcal_guest3");
		attend(guest, eventId);
		assertThat(entries.findBySource(guest.userId(), new CalendarEntrySource("EVENT", eventId))).isPresent();

		mockMvc.perform(delete("/events/" + eventId + "/attendees")
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "evcal-leave-" + UUID.randomUUID()))
			.andExpect(status().isOk());

		// Sair não é cancelar: a entrada some, porque o evento continua de pé para os outros.
		assertThat(entries.findBySource(guest.userId(), new CalendarEntrySource("EVENT", eventId))).isEmpty();
		assertThat(agenda(guest).size()).isZero();
	}

	@Test
	void deletingTheEventRemovesTheEntryFromEveryone() throws Exception {
		var host = new TestAuthHelper(mockMvc, objectMapper).authenticate("evcal_host4");
		String eventId = create(host, "Encontro cancelado", "2030-08-14", "17:00");

		var guest = new TestAuthHelper(mockMvc, objectMapper).authenticate("evcal_guest4");
		attend(guest, eventId);

		mockMvc.perform(delete("/events/" + eventId)
				.header("X-Session-Token", host.token())
				.header("Idempotency-Key", "evcal-delete-" + UUID.randomUUID()))
			.andExpect(status().isOk());

		assertThat(entries.findBySource(guest.userId(), new CalendarEntrySource("EVENT", eventId))).isEmpty();
	}

	@Test
	void eventOccupiesOneHourBecauseItHasNoEndTime() throws Exception {
		var host = new TestAuthHelper(mockMvc, objectMapper).authenticate("evcal_host5");
		String eventId = create(host, "Encontro de uma hora", "2030-08-15", "10:00");

		var guest = new TestAuthHelper(mockMvc, objectMapper).authenticate("evcal_guest5");
		attend(guest, eventId);

		CalendarEntry entry = entries.findBySource(guest.userId(), new CalendarEntrySource("EVENT", eventId))
			.orElseThrow();
		// O evento só guarda o começo; na agenda ele ocupa uma hora — nunca duração zero.
		assertThat(entry.startsAt()).isEqualTo(Instant.parse("2030-08-15T13:00:00Z"));
		assertThat(entry.endsAt()).isEqualTo(Instant.parse("2030-08-15T14:00:00Z"));
	}

	private JsonNode agenda(TestAuthHelper.AuthSession session) throws Exception {
		Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
		Instant to = Instant.now().plus(3650, ChronoUnit.DAYS);
		MvcResult result = mockMvc
			.perform(get("/calendar/entries?from=" + from + "&to=" + to)
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private String create(TestAuthHelper.AuthSession owner, String title, String date, String time)
			throws Exception {
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("title", title);
		body.put("date", date);
		if (time != null) {
			body.put("time", time);
		}
		body.put("format", "Online");
		body.put("host", "Rede");
		MvcResult created = mockMvc.perform(post("/events")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "evcal-create-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	private void attend(TestAuthHelper.AuthSession guest, String eventId) throws Exception {
		mockMvc.perform(post("/events/" + eventId + "/attendees")
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "evcal-attend-" + UUID.randomUUID()))
			.andExpect(status().isOk());
	}
}
