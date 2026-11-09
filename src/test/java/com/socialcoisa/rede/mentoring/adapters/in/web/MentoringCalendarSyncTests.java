package com.socialcoisa.rede.mentoring.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * A sessão de mentoria marcada aparece na agenda de quem marcou, e o cancelamento chega junto.
 *
 * <p>Este teste é o que prova a ligação de verdade: agenda gravada pelo serviço de origem, dono da
 * entrada sendo o mentorado (quem paga e comparece), e a leitura pela rota da agenda devolvendo o
 * compromisso com `owner=true` e `sourceType=MENTORING`. Sem isso a promessa "mentoria na agenda"
 * seria só um comentário no código.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MentoringCalendarSyncTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private CalendarEntryRepository entries;

	@Test
	void bookingASessionPutsItOnTheMenteesCalendar() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("cal_sync_mentor");
		String mentorId = createMentor(owner);
		String slotId = futureSlot(owner, mentorId);

		var mentee = new TestAuthHelper(mockMvc, objectMapper).authenticate("cal_sync_mentee");
		String sessionId = book(mentee, mentorId, slotId);

		CalendarEntry entry = entries.findBySource(mentee.userId(), new CalendarEntrySource("MENTORING", sessionId))
			.orElseThrow();

		assertThat(entry.active()).isTrue();
		assertThat(entry.title()).startsWith("Mentoria: ");
		assertThat(entry.description()).isEqualTo("Quero aprender Spring");

		JsonNode agenda = agenda(mentee);
		assertThat(agenda.size()).isEqualTo(1);
		assertThat(agenda.get(0).get("entry").get("id").asText()).isEqualTo(entry.id());
		assertThat(agenda.get(0).get("entry").get("sourceType").asText()).isEqualTo("MENTORING");
		assertThat(agenda.get(0).get("owner").asBoolean()).isTrue();

		// O mentor não ganha o compromisso na agenda dele: quem organiza é quem vai comparecer.
		assertThat(agenda(owner).size()).isZero();
	}

	@Test
	void cancellingTheSessionCancelsTheCalendarEntry() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("cal_sync_cancel_mentor");
		String mentorId = createMentor(owner);
		String slotId = futureSlot(owner, mentorId);

		var mentee = new TestAuthHelper(mockMvc, objectMapper).authenticate("cal_sync_cancel_mentee");
		String sessionId = book(mentee, mentorId, slotId);

		mockMvc.perform(post("/mentors/" + mentorId + "/sessions/" + sessionId + "/cancel")
				.header("X-Session-Token", mentee.token())
				.header("Idempotency-Key", "cancel-" + UUID.randomUUID()))
			.andExpect(status().isOk());

		CalendarEntry entry = entries.findBySource(mentee.userId(), new CalendarEntrySource("MENTORING", sessionId))
			.orElseThrow();
		// Cancelar não apaga: a linha continua, com data, para a agenda e o .ics mostrarem o
		// cancelamento em vez de o compromisso sumir sem explicação.
		assertThat(entry.active()).isFalse();
		assertThat(entry.cancelledAt()).isNotNull();
		assertThat(agenda(mentee).get(0).get("entry").get("state").asText()).isEqualTo("CANCELLED");
	}

	private JsonNode agenda(TestAuthHelper.AuthSession session) throws Exception {
		Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
		Instant to = Instant.now().plus(60, ChronoUnit.DAYS);
		MvcResult result = mockMvc
			.perform(get("/calendar/entries?from=" + from + "&to=" + to)
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private String createMentor(TestAuthHelper.AuthSession owner) throws Exception {
		MvcResult created = mockMvc.perform(post("/mentors")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mentor-create-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("expertise", List.of("Java"), "price", "Gratuito"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	/** Horário explícito no futuro: a disponibilidade semanal depende do relógio e do dia da semana. */
	private String futureSlot(TestAuthHelper.AuthSession owner, String mentorId) throws Exception {
		Instant startsAt = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
		Instant endsAt = startsAt.plus(50, ChronoUnit.MINUTES);
		MvcResult created = mockMvc.perform(post("/mentors/" + mentorId + "/slots")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "slot-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("startsAt", startsAt.toString(),
					"endsAt", endsAt.toString()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	private String book(TestAuthHelper.AuthSession mentee, String mentorId, String slotId) throws Exception {
		MvcResult result = mockMvc.perform(post("/mentors/" + mentorId + "/sessions")
				.header("X-Session-Token", mentee.token())
				.header("Idempotency-Key", "book-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("slotId", slotId, "message",
					"Quero aprender Spring"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
