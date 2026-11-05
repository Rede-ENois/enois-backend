package com.socialcoisa.rede.mentoring.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Agenda do mentor: disponibilidade semanal gera horários, o dono vê quem se inscreveu e ninguém
 * mais vê a agenda de inscrições.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MentorAgendaTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void weeklyAvailabilityGeneratesSlotsAndKeepsBookedOnes() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_agenda_owner");
		String mentorId = createMentor(owner);

		// Segunda, quarta e sexta às 19:00 e às 20:00.
		putAvailability(owner, mentorId, List.of(
			Map.of("dayOfWeek", 1, "times", List.of("19:00", "20:00")),
			Map.of("dayOfWeek", 3, "times", List.of("19:00")),
			Map.of("dayOfWeek", 5, "times", List.of("19:00"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].dayOfWeek").value(1))
			.andExpect(jsonPath("$[0].times.length()").value(2));

		JsonNode slots = slots(mentorId);
		assertThat(slots.size()).isGreaterThan(8);
		String bookedSlot = slots.get(0).get("id").asText();

		var mentee = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_agenda_mentee");
		book(mentee, mentorId, bookedSlot, "Quero aprender Spring").andExpect(status().isCreated());

		// Salvar de novo as mesmas regras não duplica horário nem apaga o reservado.
		putAvailability(owner, mentorId, List.of(
			Map.of("dayOfWeek", 1, "times", List.of("19:00", "20:00")),
			Map.of("dayOfWeek", 3, "times", List.of("19:00")),
			Map.of("dayOfWeek", 5, "times", List.of("19:00"))))
			.andExpect(status().isOk());
		JsonNode afterSame = slots(mentorId);
		assertThat(afterSame.size()).isEqualTo(slots.size());
		assertThat(containsId(afterSame, bookedSlot)).isTrue();

		// Tirar a sexta retira só os horários livres daquele dia; o reservado fica.
		putAvailability(owner, mentorId, List.of(
			Map.of("dayOfWeek", 1, "times", List.of("19:00", "20:00")),
			Map.of("dayOfWeek", 3, "times", List.of("19:00"))))
			.andExpect(status().isOk());
		JsonNode afterRemoval = slots(mentorId);
		assertThat(afterRemoval.size()).isLessThan(afterSame.size());
		assertThat(containsId(afterRemoval, bookedSlot)).isTrue();
	}

	@Test
	void ownerSeesWhoBookedAndTheMessage() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_inbox_owner");
		String mentorId = createMentor(owner);
		putAvailability(owner, mentorId, List.of(Map.of("dayOfWeek", 2, "times", List.of("18:30"))))
			.andExpect(status().isOk());
		String slotId = slots(mentorId).get(0).get("id").asText();

		var mentee = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_inbox_mentee");
		book(mentee, mentorId, slotId, "Preciso de ajuda com entrevista").andExpect(status().isCreated());

		mockMvc.perform(get("/mentors/" + mentorId + "/sessions")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].menteeId").value(mentee.userId()))
			.andExpect(jsonPath("$[0].menteeUsername").isNotEmpty())
			.andExpect(jsonPath("$[0].message").value("Preciso de ajuda com entrevista"))
			.andExpect(jsonPath("$[0].status").value("SCHEDULED"))
			.andExpect(jsonPath("$[0].startsAt").isNotEmpty());

		// Inscrito e terceiro não enxergam a agenda de inscrições do mentor.
		mockMvc.perform(get("/mentors/" + mentorId + "/sessions")
				.header("X-Session-Token", mentee.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/mentors/" + mentorId + "/sessions"))
			.andExpect(status().isUnauthorized());

		// Nem a disponibilidade é pública.
		mockMvc.perform(get("/mentors/" + mentorId + "/availability")
				.header("X-Session-Token", mentee.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void availabilityValidatesDayAndTime() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_agenda_invalid");
		String mentorId = createMentor(owner);

		putAvailability(owner, mentorId, List.of(Map.of("dayOfWeek", 8, "times", List.of("19:00"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_DAY_OF_WEEK"));

		putAvailability(owner, mentorId, List.of(Map.of("dayOfWeek", 1, "times", List.of("25:00"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_TIME"));
	}

	@Test
	void mentorPublishesExternalMeetingRoomForMentees() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_meeting_owner");

		// Link https no cadastro do mentor.
		MvcResult created = mockMvc.perform(post("/mentors")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mentor-create-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"expertise", List.of("Java"),
					"price", "Gratuito",
					"meetingUrl", "https://meet.google.com/abc-defg-hij"))))
			.andExpect(status().isCreated())
			.andReturn();
		String mentorId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		// A vitrine pública não anuncia a sala; só o dono a recebe em /room. Quem agenda recebe
		// o link na própria sessão marcada. Link não-https é recusado.
		mockMvc.perform(get("/mentors/" + mentorId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.meetingUrl").doesNotExist());
		mockMvc.perform(get("/mentors/" + mentorId + "/room")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.meetingUrl").value("https://meet.google.com/abc-defg-hij"));

		mockMvc.perform(patch("/mentors/" + mentorId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mentor-update-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"expertise", List.of("Java"),
					"price", "Gratuito",
					"meetingUrl", "http://sala.insegura.example/x"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_MEETING_URL"));

		// Remover o link também é permitido (string vazia).
		mockMvc.perform(patch("/mentors/" + mentorId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mentor-update-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"expertise", List.of("Java"),
					"price", "Gratuito",
					"meetingUrl", ""))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.meetingUrl").doesNotExist());
	}

	private org.springframework.test.web.servlet.ResultActions putAvailability(TestAuthHelper.AuthSession owner,
			String mentorId, List<Map<String, Object>> items) throws Exception {
		return mockMvc.perform(put("/mentors/" + mentorId + "/availability")
			.header("X-Session-Token", owner.token())
			.header("Idempotency-Key", "mentor-availability-" + UUID.randomUUID())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("items", items))));
	}

	private org.springframework.test.web.servlet.ResultActions book(TestAuthHelper.AuthSession mentee, String mentorId,
			String slotId, String message) throws Exception {
		return mockMvc.perform(post("/mentors/" + mentorId + "/sessions")
			.header("X-Session-Token", mentee.token())
			.header("Idempotency-Key", "mentor-book-" + UUID.randomUUID())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("slotId", slotId, "message", message))));
	}

	private JsonNode slots(String mentorId) throws Exception {
		MvcResult result = mockMvc.perform(get("/mentors/" + mentorId + "/slots"))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
	}

	private boolean containsId(JsonNode items, String id) {
		for (JsonNode item : items) {
			if (id.equals(item.get("id").asText())) {
				return true;
			}
		}
		return false;
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
}
