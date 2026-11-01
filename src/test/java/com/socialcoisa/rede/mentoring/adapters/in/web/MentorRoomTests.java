package com.socialcoisa.rede.mentoring.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
 * Sala da sessão de mentoria: o padrão é uma sala Jitsi criada por sessão (sem configuração), e o
 * mentor pode escolher uma sala permanente do Jitsi ou uma sala própria (Meet e afins). O link é
 * congelado no agendamento e só quem participa vê.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MentorRoomTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void defaultIsAJitsiRoomCreatedPerSession() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_default_owner");
		String mentorId = createMentor(owner, Map.of("expertise", List.of("Java"), "price", "Gratuito"));
		String firstSlot = slot(owner, mentorId);
		String secondSlot = slot(owner, mentorId);

		var menteeA = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_default_a");
		var menteeB = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_default_b");
		book(menteeA, mentorId, firstSlot).andExpect(status().isCreated());
		book(menteeB, mentorId, secondSlot).andExpect(status().isCreated());

		JsonNode mineA = mySessions(menteeA);
		JsonNode mineB = mySessions(menteeB);
		String roomA = mineA.get(0).get("roomUrl").asText();
		String roomB = mineB.get(0).get("roomUrl").asText();

		assertThat(roomA).startsWith("https://meet.jit.si/rede-mentoria-");
		assertThat(roomB).startsWith("https://meet.jit.si/rede-mentoria-");
		// Sala própria por sessão: dois mentorados nunca caem na mesma sala.
		assertThat(roomA).isNotEqualTo(roomB);
		assertThat(mineA.get(0).get("roomMode").asText()).isEqualTo("JITSI_SESSION");

		// O mentor vê o mesmo link que o mentorado.
		JsonNode inbox = mentorSessions(owner, mentorId);
		String firstSessionId = mineA.get(0).get("id").asText();
		assertThat(roomOf(inbox, firstSessionId)).isEqualTo(roomA);
	}

	@Test
	void mentorCanUseTheirOwnRoomAndItIsRequiredForThatMode() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_custom_owner");

		// Sala própria sem link é recusada.
		mockMvc.perform(post("/mentors")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mentor-room-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"expertise", List.of("Java"), "price", "Gratuito", "roomMode", "CUSTOM"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MEETING_URL_REQUIRED"));

		String mentorId = createMentor(owner, Map.of(
			"expertise", List.of("Java"),
			"price", "Gratuito",
			"roomMode", "CUSTOM",
			"meetingUrl", "https://meet.google.com/abc-defg-hij"));

		String slotId = slot(owner, mentorId);
		var mentee = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_custom_mentee");
		book(mentee, mentorId, slotId).andExpect(status().isCreated());

		JsonNode mine = mySessions(mentee);
		assertThat(mine.get(0).get("roomUrl").asText()).isEqualTo("https://meet.google.com/abc-defg-hij");
		assertThat(mine.get(0).get("roomMode").asText()).isEqualTo("CUSTOM");

		// Sala própria não tem dial-in do Jitsi.
		mockMvc.perform(get("/mentors/" + mentorId + "/sessions/" + mine.get(0).get("id").asText() + "/dial-in")
				.header("X-Session-Token", mentee.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NOT_A_JITSI_ROOM"));
	}

	@Test
	void permanentJitsiRoomIsStableAndOnlyTheOwnerSeesIt() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_perm_owner");
		String mentorId = createMentor(owner, Map.of(
			"expertise", List.of("Java"),
			"price", "Gratuito",
			"roomMode", "JITSI_PERMANENT"));

		MvcResult room = mockMvc.perform(get("/mentors/" + mentorId + "/room")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roomMode").value("JITSI_PERMANENT"))
			.andReturn();
		String permanentUrl = objectMapper.readTree(room.getResponse().getContentAsString()).get("roomUrl").asText();
		assertThat(permanentUrl).startsWith("https://meet.jit.si/rede-mentor-");

		// Duas sessões diferentes usam a mesma sala permanente.
		var menteeA = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_perm_a");
		var menteeB = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_perm_b");
		book(menteeA, mentorId, slot(owner, mentorId)).andExpect(status().isCreated());
		book(menteeB, mentorId, slot(owner, mentorId)).andExpect(status().isCreated());

		assertThat(mySessions(menteeA).get(0).get("roomUrl").asText()).isEqualTo(permanentUrl);
		assertThat(mySessions(menteeB).get(0).get("roomUrl").asText()).isEqualTo(permanentUrl);

		// O link permanente não vai para a vitrine pública nem para quem não é dono.
		mockMvc.perform(get("/mentors/" + mentorId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roomMode").value("JITSI_PERMANENT"))
			.andExpect(jsonPath("$.meetingUrl").doesNotExist());
		mockMvc.perform(get("/mentors/" + mentorId + "/room")
				.header("X-Session-Token", menteeA.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/mentors/" + mentorId + "/room"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void bookedSessionKeepsTheRoomItWasBookedWith() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_freeze_owner");
		String mentorId = createMentor(owner, Map.of("expertise", List.of("Java"), "price", "Gratuito"));
		String slotId = slot(owner, mentorId);
		var mentee = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_freeze_mentee");
		book(mentee, mentorId, slotId).andExpect(status().isCreated());
		String bookedRoom = mySessions(mentee).get(0).get("roomUrl").asText();

		// O mentor troca a sala do perfil depois do agendamento.
		mockMvc.perform(patch("/mentors/" + mentorId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mentor-room-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"expertise", List.of("Java"),
					"price", "Gratuito",
					"roomMode", "CUSTOM",
					"meetingUrl", "https://meet.google.com/nova-sala"))))
			.andExpect(status().isOk());

		// A sessão já marcada continua na sala antiga; a próxima nasce na nova.
		assertThat(mySessions(mentee).get(0).get("roomUrl").asText()).isEqualTo(bookedRoom);

		var other = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_freeze_other");
		book(other, mentorId, slot(owner, mentorId)).andExpect(status().isCreated());
		assertThat(mySessions(other).get(0).get("roomUrl").asText()).isEqualTo("https://meet.google.com/nova-sala");
	}

	@Test
	void switchingBackToJitsiDropsTheExternalLink() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_switch_owner");
		String mentorId = createMentor(owner, Map.of(
			"expertise", List.of("Java"),
			"price", "Gratuito",
			"roomMode", "CUSTOM",
			"meetingUrl", "https://meet.google.com/velha-sala"));
		mockMvc.perform(get("/mentors/" + mentorId))
			.andExpect(jsonPath("$.meetingUrl").value("https://meet.google.com/velha-sala"));

		// Volta para o Jitsi sem mandar link: o Meet antigo nao pode continuar anunciado no perfil.
		mockMvc.perform(patch("/mentors/" + mentorId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mentor-room-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"expertise", List.of("Java"),
					"price", "Gratuito",
					"roomMode", "JITSI_PERMANENT",
					"jitsiRoom", "Sala Da Mel"))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/mentors/" + mentorId))
			.andExpect(jsonPath("$.roomMode").value("JITSI_PERMANENT"))
			.andExpect(jsonPath("$.meetingUrl").doesNotExist());

		MvcResult room = mockMvc.perform(get("/mentors/" + mentorId + "/room")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode view = objectMapper.readTree(room.getResponse().getContentAsString());
		// O nome pedido e normalizado para o formato que o Jitsi aceita.
		assertThat(view.get("jitsiRoom").asText()).isEqualTo("sala-da-mel");
		assertThat(view.get("roomUrl").asText()).isEqualTo("https://meet.jit.si/sala-da-mel");
	}

	@Test
	void dialInIsOnlyForParticipantsAndAnswersForJitsiRooms() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_dial_owner");
		String mentorId = createMentor(owner, Map.of("expertise", List.of("Java"), "price", "Gratuito"));
		var mentee = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_dial_mentee");
		book(mentee, mentorId, slot(owner, mentorId)).andExpect(status().isCreated());
		String sessionId = mySessions(mentee).get(0).get("id").asText();

		var stranger = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_dial_stranger");
		mockMvc.perform(get("/mentors/" + mentorId + "/sessions/" + sessionId + "/dial-in")
				.header("X-Session-Token", stranger.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/mentors/" + mentorId + "/sessions/" + sessionId + "/dial-in"))
			.andExpect(status().isUnauthorized());

		// A API do Jitsi pode não responder (CI sem rede): o link continua valendo, a lista vem vazia.
		mockMvc.perform(get("/mentors/" + mentorId + "/sessions/" + sessionId + "/dial-in")
				.header("X-Session-Token", mentee.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.conference").value(org.hamcrest.Matchers.containsString("@conference.")))
			.andExpect(jsonPath("$.numbers").isArray());
	}

	private String createMentor(TestAuthHelper.AuthSession owner, Map<String, Object> payload) throws Exception {
		MvcResult created = mockMvc.perform(post("/mentors")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mentor-room-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(payload)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	private String slot(TestAuthHelper.AuthSession owner, String mentorId) throws Exception {
		MvcResult slot = mockMvc.perform(post("/mentors/" + mentorId + "/slots")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mentor-room-slot-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"startsAt", Instant.now().plusSeconds(86_400).toString(),
					"endsAt", Instant.now().plusSeconds(90_000).toString()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(slot.getResponse().getContentAsString()).get("id").asText();
	}

	private org.springframework.test.web.servlet.ResultActions book(TestAuthHelper.AuthSession mentee, String mentorId,
			String slotId) throws Exception {
		return mockMvc.perform(post("/mentors/" + mentorId + "/sessions")
			.header("X-Session-Token", mentee.token())
			.header("Idempotency-Key", "mentor-room-book-" + UUID.randomUUID())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("slotId", slotId, "message", "oi"))));
	}

	private JsonNode mySessions(TestAuthHelper.AuthSession mentee) throws Exception {
		MvcResult result = mockMvc.perform(get("/mentors/sessions/mine")
				.header("X-Session-Token", mentee.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode mentorSessions(TestAuthHelper.AuthSession owner, String mentorId) throws Exception {
		MvcResult result = mockMvc.perform(get("/mentors/" + mentorId + "/sessions")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private String roomOf(JsonNode sessions, String sessionId) {
		for (JsonNode session : sessions) {
			if (sessionId.equals(session.get("id").asText())) {
				return session.get("roomUrl").asText();
			}
		}
		throw new AssertionError("sessao " + sessionId + " nao apareceu na lista");
	}

}
