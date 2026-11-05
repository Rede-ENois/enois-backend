package com.socialcoisa.rede.mentoring.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sala da sessão de mentoria: o padrão é uma sala do Epapo criada por sessão (sem configuração), e
 * o mentor pode escolher uma sala permanente ou uma sala própria (Meet e afins). O endereço é
 * congelado no agendamento e só quem participa vê; a entrada na sala do Epapo passa por token.
 */
@SpringBootTest(properties = {
		"rede.calls.epapo.url=https://meet.example.test",
		"rede.calls.gateway.authorize-token=segredo-de-teste-da-ponte"
})
@AutoConfigureMockMvc
class MentorRoomTests {

	private static final String GATEWAY_SECRET = "segredo-interno-do-gateway";
	private static final AtomicInteger TOKENS = new AtomicInteger();
	private static final HttpServer GATEWAY = startGateway();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@DynamicPropertySource
	static void gatewayProperties(DynamicPropertyRegistry registry) {
		registry.add("rede.calls.epapo.gateway-url", () -> "http://127.0.0.1:" + GATEWAY.getAddress().getPort());
		registry.add("rede.calls.epapo.internal-token", () -> GATEWAY_SECRET);
	}

	@AfterAll
	static void stopGateway() {
		GATEWAY.stop(0);
	}

	@Test
	void defaultIsAnEpapoRoomCreatedPerSession() throws Exception {
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

		assertThat(roomA).startsWith("https://meet.example.test/rede-mentoria-");
		assertThat(roomB).startsWith("https://meet.example.test/rede-mentoria-");
		// Uma sala por sessão: dois mentorados nunca caem na mesma sala.
		assertThat(roomA).isNotEqualTo(roomB);
		assertThat(mineA.get(0).get("roomMode").asText()).isEqualTo("EPAPO_SESSION");

		// O mentor vê o mesmo link que o mentorado.
		String sessionA = mineA.get(0).get("id").asText();
		assertThat(roomOf(mentorSessions(owner, mentorId), sessionA)).isEqualTo(roomA);
	}

	@Test
	void ownRoomIsARequirementOfThatMode() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_custom_owner");
		mockMvc.perform(post("/mentors")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mentor-room-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"expertise", List.of("Java"), "price", "Gratuito", "roomMode", "CUSTOM"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MEETING_URL_REQUIRED"));
	}

	@Test
	void ownRoomIsALinkAndJoinDoesNotAskTheServerForAToken() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_link_owner");
		String mentorId = createMentor(owner, Map.of(
			"expertise", List.of("Java"),
			"price", "Gratuito",
			"roomMode", "CUSTOM",
			"meetingUrl", "https://meet.google.com/abc-defg-hij"));

		var mentee = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_link_mentee");
		book(mentee, mentorId, slot(owner, mentorId)).andExpect(status().isCreated());

		JsonNode mine = mySessions(mentee);
		String sessionId = mine.get(0).get("id").asText();
		assertThat(mine.get(0).get("roomUrl").asText()).isEqualTo("https://meet.google.com/abc-defg-hij");
		assertThat(mine.get(0).get("roomMode").asText()).isEqualTo("CUSTOM");

		MvcResult joined = mockMvc.perform(post("/mentors/" + mentorId + "/sessions/" + sessionId + "/join")
				.header("X-Session-Token", mentee.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roomUrl").value("https://meet.google.com/abc-defg-hij"))
			.andReturn();
		JsonNode access = objectMapper.readTree(joined.getResponse().getContentAsString());
		assertThat(access.get("jwt").isNull()).isTrue();
	}

	@Test
	void permanentRoomIsTheSameForEverySessionAndOnlyTheOwnerSeesIt() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_perm_owner");
		String mentorId = createMentor(owner, Map.of(
			"expertise", List.of("Java"),
			"price", "Gratuito",
			"roomMode", "EPAPO_PERMANENT"));

		MvcResult panel = panel(owner, mentorId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roomMode").value("EPAPO_PERMANENT"))
			.andExpect(jsonPath("$.roomSlug").value(Matchers.startsWith("rede-mentor-")))
			.andExpect(jsonPath("$.roomUrl").value(Matchers.startsWith("https://meet.example.test/rede-mentor-")))
			.andReturn();
		String room = objectMapper.readTree(panel.getResponse().getContentAsString()).get("roomUrl").asText();

		var menteeA = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_perm_a");
		var menteeB = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_perm_b");
		book(menteeA, mentorId, slot(owner, mentorId)).andExpect(status().isCreated());
		book(menteeB, mentorId, slot(owner, mentorId)).andExpect(status().isCreated());

		assertThat(mySessions(menteeA).get(0).get("roomUrl").asText()).isEqualTo(room);
		assertThat(mySessions(menteeB).get(0).get("roomUrl").asText()).isEqualTo(room);

		// O link permanente não vai para a vitrine pública nem para quem não é dono.
		mockMvc.perform(get("/mentors/" + mentorId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roomMode").value("EPAPO_PERMANENT"))
			.andExpect(jsonPath("$.roomUrl").doesNotExist());
		panel(menteeA, mentorId).andExpect(status().isForbidden());
		mockMvc.perform(get("/mentors/" + mentorId + "/room"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void bookedSessionKeepsTheRoomItWasBookedWith() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_freeze_owner");
		String mentorId = createMentor(owner, Map.of("expertise", List.of("Java"), "price", "Gratuito"));
		var mentee = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_freeze_mentee");
		book(mentee, mentorId, slot(owner, mentorId)).andExpect(status().isCreated());
		String bookedRoom = mySessions(mentee).get(0).get("roomUrl").asText();

		// O mentor troca a sala do perfil depois do agendamento.
		updateMentor(owner, mentorId, Map.of(
			"expertise", List.of("Java"),
			"price", "Gratuito",
			"roomMode", "CUSTOM",
			"meetingUrl", "https://meet.google.com/nova-sala")).andExpect(status().isOk());

		// A sessão já marcada continua na sala antiga; a próxima nasce na nova.
		assertThat(mySessions(mentee).get(0).get("roomUrl").asText()).isEqualTo(bookedRoom);

		var other = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_freeze_other");
		book(other, mentorId, slot(owner, mentorId)).andExpect(status().isCreated());
		assertThat(mySessions(other).get(0).get("roomUrl").asText()).isEqualTo("https://meet.google.com/nova-sala");
	}

	@Test
	void switchingBackToEpapoDropsTheExternalLink() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_switch_owner");
		String mentorId = createMentor(owner, Map.of(
			"expertise", List.of("Java"),
			"price", "Gratuito",
			"roomMode", "CUSTOM",
			"meetingUrl", "https://meet.google.com/velha-sala"));
		panel(owner, mentorId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.meetingUrl").value("https://meet.google.com/velha-sala"));

		// Volta para o Epapo com um nome escolhido: o Meet antigo não pode continuar no perfil.
		updateMentor(owner, mentorId, Map.of(
			"expertise", List.of("Java"),
			"price", "Gratuito",
			"roomMode", "EPAPO_PERMANENT",
			"roomSlug", "Sala Da Mel")).andExpect(status().isOk());

		// O nome pedido é normalizado para o formato que o servidor de reunião aceita.
		panel(owner, mentorId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roomMode").value("EPAPO_PERMANENT"))
			.andExpect(jsonPath("$.meetingUrl").doesNotExist())
			.andExpect(jsonPath("$.roomSlug").value("sala-da-mel"))
			.andExpect(jsonPath("$.roomUrl").value("https://meet.example.test/sala-da-mel"));
	}

	@Test
	void joinIsOnlyForParticipantsAndComesWithAToken() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_join_owner");
		String mentorId = createMentor(owner, Map.of("expertise", List.of("Java"), "price", "Gratuito"));
		var mentee = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_join_mentee");
		book(mentee, mentorId, slot(owner, mentorId)).andExpect(status().isCreated());
		String sessionId = mySessions(mentee).get(0).get("id").asText();
		String joinPath = "/mentors/" + mentorId + "/sessions/" + sessionId + "/join";

		var stranger = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_join_stranger");
		mockMvc.perform(post(joinPath).header("X-Session-Token", stranger.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(post(joinPath))
			.andExpect(status().isUnauthorized());

		// Quem participa recebe o token do gateway; o segredo de assinatura nunca sai do servidor.
		MvcResult joined = mockMvc.perform(post(joinPath).header("X-Session-Token", mentee.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId").value(sessionId))
			.andExpect(jsonPath("$.roomUrl").value(Matchers.startsWith("https://meet.example.test/rede-mentoria-")))
			.andExpect(jsonPath("$.jwt").value(Matchers.startsWith("epapo-token-")))
			.andExpect(jsonPath("$.tokenTtlSeconds").value(Matchers.greaterThan(0)))
			.andReturn();
		assertThat(joined.getResponse().getContentAsString()).doesNotContain(GATEWAY_SECRET);
	}

	@Test
	void gatewayEntryRequiresTheSharedSecretAndAnswersForParticipants() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_gate_owner");
		String mentorId = createMentor(owner, Map.of("expertise", List.of("Java"), "price", "Gratuito"));
		var mentee = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_gate_mentee");
		book(mentee, mentorId, slot(owner, mentorId)).andExpect(status().isCreated());
		JsonNode session = mySessions(mentee).get(0);
		String room = session.get("roomUrl").asText().substring("https://meet.example.test/".length());
		String path = "/mentoring/" + session.get("id").asText() + "/gateway/entry/";
		String secret = "segredo-de-teste-da-ponte";

		// Sem o segredo da ponte nada é autorizado, nem com o id certo.
		mockMvc.perform(post(path + mentee.userId())).andExpect(status().isUnauthorized());
		mockMvc.perform(post(path + mentee.userId()).header("X-Enois-Authorize", "errado"))
			.andExpect(status().isUnauthorized());

		// Quem participa é autorizado, com a sala e o nome vindo do servidor.
		mockMvc.perform(post(path + mentee.userId()).header("X-Enois-Authorize", secret))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.room").value(room))
			.andExpect(jsonPath("$.state").value("SCHEDULED"))
			.andExpect(jsonPath("$.moderator").value(false));

		// O mentor é moderador da própria sala.
		mockMvc.perform(post(path + owner.userId()).header("X-Enois-Authorize", secret))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.room").value(room))
			.andExpect(jsonPath("$.moderator").value(true));

		var stranger = new TestAuthHelper(mockMvc, objectMapper).authenticate("mentor_room_gate_stranger");
		mockMvc.perform(post(path + stranger.userId()).header("X-Enois-Authorize", secret))
			.andExpect(status().isForbidden());
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

	private ResultActions updateMentor(TestAuthHelper.AuthSession owner, String mentorId, Map<String, Object> payload)
			throws Exception {
		return mockMvc.perform(patch("/mentors/" + mentorId)
			.header("X-Session-Token", owner.token())
			.header("Idempotency-Key", "mentor-room-" + UUID.randomUUID())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(payload)));
	}

	private ResultActions panel(TestAuthHelper.AuthSession owner, String mentorId) throws Exception {
		return mockMvc.perform(get("/mentors/" + mentorId + "/room")
			.header("X-Session-Token", owner.token()));
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

	private ResultActions book(TestAuthHelper.AuthSession mentee, String mentorId, String slotId) throws Exception {
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

	/** Gateway do Epapo falso: aqui só o contrato de {@code /auth/token} importa. */
	private static HttpServer startGateway() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/auth/token", exchange -> {
				String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				boolean authorized = GATEWAY_SECRET.equals(exchange.getRequestHeaders().getFirst("X-Epapo-Internal"))
						&& body.contains("\"sessionId\"");
				reply(exchange, authorized ? 200 : 401,
					authorized ? "{\"jwt\":\"epapo-token-" + TOKENS.incrementAndGet() + "\"}"
							: "{\"error\":\"unauthorized\"}");
			});
			server.start();
			return server;
		}
		catch (IOException failure) {
			throw new IllegalStateException("nao foi possivel subir o gateway falso", failure);
		}
	}

	private static void reply(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

}
