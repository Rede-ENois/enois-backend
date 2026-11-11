package com.socialcoisa.rede.mentoring.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Apagar o perfil de mentor não pode ser bloqueado pelo que ele publicou: horários, regras de
 * disponibilidade e sessões são dados do perfil. As sessões agendadas são canceladas (com o escrow
 * devolvido e o compromisso cancelado na agenda de quem marcou); as concluídas ficam como histórico.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MentorDeletionTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private CoinService coins;

	@Autowired
	private CalendarEntryRepository entries;

	@Test
	void deletingAMentorWithPublishedSlotsSucceeds() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_delete_slots");
		String mentorId = createMentor(owner, null);
		createSlot(owner, mentorId, Instant.now().plusSeconds(86_400));

		deleteMentor(owner, mentorId).andExpect(status().isOk());

		mockMvc.perform(get("/mentors/" + mentorId))
			.andExpect(status().isNotFound());
	}

	@Test
	void deletingAMentorCancelsScheduledSessionsAndRefundsTheMentee() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_delete_session_owner");
		var mentee = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_delete_session_mentee");
		long price = CoinService.FAIS(120);
		String mentorId = createMentor(owner, price);
		String slotId = createSlot(owner, mentorId, Instant.now().plusSeconds(86_400));

		fund(mentee, CoinService.FAIS(300), "test-mentor-delete-fund");
		long menteeBefore = coins.balance(mentee.userId());
		String sessionId = book(mentee, mentorId, slotId);
		assertThat(coins.balance(mentee.userId())).isEqualTo(menteeBefore - price);

		deleteMentor(owner, mentorId).andExpect(status().isOk());

		// O escrow volta integral: o perfil sumiu, a sessão não vai acontecer.
		assertThat(coins.balance(mentee.userId())).isEqualTo(menteeBefore);
		mockMvc.perform(get("/mentors/sessions/mine").header("X-Session-Token", mentee.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + sessionId + "')].status").value("CANCELLED"));

		CalendarEntry entry = entries.findBySource(mentee.userId(), new CalendarEntrySource("MENTORING", sessionId))
			.orElseThrow();
		assertThat(entry.active()).isFalse();
		assertThat(entry.cancelledAt()).isNotNull();
	}

	private ResultActions deleteMentor(TestAuthHelper.AuthSession owner, String mentorId) throws Exception {
		return mockMvc.perform(delete("/mentors/" + mentorId)
			.header("X-Session-Token", owner.token())
			.header("Idempotency-Key", "mentor-delete-" + UUID.randomUUID()));
	}

	private String createMentor(TestAuthHelper.AuthSession owner, Long sparksPrice) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("expertise", List.of("Java"));
		body.put("price", "Gratuito");
		if (sparksPrice != null) {
			body.put("sparksPrice", sparksPrice);
		}
		MvcResult created = mockMvc.perform(post("/mentors")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mentor-create-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	private String createSlot(TestAuthHelper.AuthSession owner, String mentorId, Instant startsAt) throws Exception {
		MvcResult slot = mockMvc.perform(post("/mentors/" + mentorId + "/slots")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mentor-slot-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("startsAt", startsAt.toString(),
					"endsAt", startsAt.plusSeconds(3_600).toString()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(slot.getResponse().getContentAsString()).get("id").asText();
	}

	private String book(TestAuthHelper.AuthSession mentee, String mentorId, String slotId) throws Exception {
		MvcResult result = mockMvc.perform(post("/mentors/" + mentorId + "/sessions")
				.header("X-Session-Token", mentee.token())
				.header("Idempotency-Key", "mentor-book-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("slotId", slotId, "message", "oi"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void fund(TestAuthHelper.AuthSession user, long amount, String prefix) {
		coins.reward(user.userId(), amount, prefix + "-" + user.userId(), "Saldo de teste");
		coins.ensureGenesisFor(user.userId());
	}
}
