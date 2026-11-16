package com.socialcoisa.rede.mentoring.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
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
import org.springframework.test.web.servlet.ResultActions;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Horário publicado é o que a pessoa de fato pode reservar: precisa de começo e fim, o fim depois do
 * começo e o começo no futuro. Sem essa régua um horário invertido ou já vencido entra na agenda e
 * fica na lista como opção que ninguém consegue usar.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MentorSlotValidationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void slotWithoutBothEndsIsRefused() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_slot_required");
		String mentorId = createMentor(owner);

		Map<String, Object> withoutEnd = new HashMap<>();
		withoutEnd.put("startsAt", Instant.now().plusSeconds(86_400).toString());
		publish(owner, mentorId, withoutEnd)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SLOT_TIME_REQUIRED"));

		Map<String, Object> withoutStart = new HashMap<>();
		withoutStart.put("endsAt", Instant.now().plusSeconds(90_000).toString());
		publish(owner, mentorId, withoutStart)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SLOT_TIME_REQUIRED"));

		// Nenhum dos dois virou horário na agenda.
		assertThat(slots(mentorId).size()).isZero();
	}

	@Test
	void slotWhoseEndIsNotAfterTheStartIsRefused() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_slot_inverted");
		String mentorId = createMentor(owner);
		Instant start = Instant.now().plusSeconds(86_400);

		publish(owner, mentorId, Map.of("startsAt", start.toString(),
			"endsAt", start.minusSeconds(1).toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SLOT_TIME_INVERTED"));

		// Fim igual ao começo também não é duração: não sobra tempo para a sessão existir.
		publish(owner, mentorId, Map.of("startsAt", start.toString(), "endsAt", start.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SLOT_TIME_INVERTED"));

		assertThat(slots(mentorId).size()).isZero();
	}

	@Test
	void slotInThePastIsRefused() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_slot_past");
		String mentorId = createMentor(owner);
		Instant start = Instant.now().minusSeconds(3_600);

		publish(owner, mentorId, Map.of("startsAt", start.toString(),
			"endsAt", start.plusSeconds(3_600).toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SLOT_TIME_PAST"));

		assertThat(slots(mentorId).size()).isZero();

		// O horário futuro continua sendo publicado: a régua nova não fecha a agenda.
		publishFutureSlot(owner, mentorId).andExpect(status().isCreated());
		assertThat(slots(mentorId).size()).isEqualTo(1);
	}

	private ResultActions publish(TestAuthHelper.AuthSession owner, String mentorId, Map<String, Object> body)
			throws Exception {
		return mockMvc.perform(post("/mentors/" + mentorId + "/slots")
			.header("X-Session-Token", owner.token())
			.header("Idempotency-Key", "slot-valid-" + UUID.randomUUID())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private ResultActions publishFutureSlot(TestAuthHelper.AuthSession owner, String mentorId) throws Exception {
		Instant start = Instant.now().plusSeconds(86_400);
		return publish(owner, mentorId, Map.of("startsAt", start.toString(),
			"endsAt", start.plusSeconds(3_600).toString()));
	}

	private JsonNode slots(String mentorId) throws Exception {
		MvcResult result = mockMvc.perform(get("/mentors/" + mentorId + "/slots"))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
	}

	private String createMentor(TestAuthHelper.AuthSession owner) throws Exception {
		MvcResult created = mockMvc.perform(post("/mentors")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "slot-valid-mentor-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"expertise", List.of("Java"),
					"price", "Gratuito"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}
}