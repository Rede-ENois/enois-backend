package com.socialcoisa.rede.calls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

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

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * "Tamo junto" do encontro: a sala do evento, aberta a quem quiser entrar.
 *
 * <p>O que este teste tranca: a sala nasce ao vivo com quem abriu já dentro e sem tocar ninguém;
 * qualquer pessoa com sessão entra pelo id do evento; um evento que não existe não abre sala; a
 * sala de um encontro não aparece na de outro; e o aviso "está ao vivo" chega a quem criou e a quem
 * confirmou presença, e a mais ninguém.
 */
@SpringBootTest(properties = {
		"rede.calls.epapo.url=https://chamadas.example.test",
		"spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class CallEventRoomTests {

	private static final int GATEWAY_PORT = freePort();

	static final FakeEpapoGateway GATEWAY = startGateway(GATEWAY_PORT);

	static final String BRIDGE = java.util.UUID.randomUUID() + "" + java.util.UUID.randomUUID();

	static {
		System.setProperty("call.event.bridge", BRIDGE);
	}

	@DynamicPropertySource
	static void gateway(DynamicPropertyRegistry registry) {
		registry.add("rede.calls.epapo.gateway-url", () -> GATEWAY.url());
		registry.add("rede.calls.epapo.internal-token", () -> "epapo-" + BRIDGE);
		registry.add("rede.calls.gateway.authorize-token", () -> "${call.event.bridge}");
	}

	@AfterAll
	static void shutGateway() {
		GATEWAY.stop();
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void salaDoEncontroNasceAoVivoComQuemAbriuDentroESemToque() throws Exception {
		var host = auth("event-call-host1");
		String eventId = createEvent(host, "event-call-1");

		JsonNode call = startEventCall(host, eventId, "event-call-1");

		assertThat(call.get("eventId").asText()).isEqualTo(eventId);
		assertThat(call.get("modality").asText()).isEqualTo("GROUP");
		assertThat(call.get("state").asText()).isEqualTo("ACTIVE");
		assertThat(call.get("participantCount").asInt()).isEqualTo(1);
		// Ninguém é chamado no telefone: o encontro avisa que está ao vivo, não toca.
		assertThat(callNotifications(host)).noneMatch(item -> "CALL_INCOMING".equals(item.get("kind").asText()));
	}

	@Test
	void qualquerPessoaComSessaoEntraPeloIdDoEncontro() throws Exception {
		var host = auth("event-call-host2");
		var guest = auth("event-call-guest2");
		String eventId = createEvent(host, "event-call-2");
		String callId = startEventCall(host, eventId, "event-call-2").get("id").asText();

		// Quem não criou o evento e não confirmou presença entra do mesmo jeito: a sala é aberta.
		JsonNode joined = join(guest, callId);
		assertThat(joined.get("jwt").asText()).startsWith("epapo-token-");
		assertThat(joined.get("call").get("participantCount").asInt()).isEqualTo(2);
	}

	@Test
	void encontroInexistenteNaoAbreSala() throws Exception {
		var host = auth("event-call-host3");

		mockMvc.perform(post("/events/event_nao_existe/calls")
				.header("X-Session-Token", host.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"AUDIO\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));

		mockMvc.perform(get("/events/event_nao_existe/calls/open")
				.header("X-Session-Token", host.token()))
			.andExpect(status().isNotFound());
	}

	@Test
	void eventoSemACaixinhaTamoJuntoNaoAbreSala() throws Exception {
		var host = auth("event-call-host-optout");
		Map<String, Object> body = new HashMap<>(Map.of("title", "Encontro sem sala",
			"date", LocalDate.now().plusMonths(2).toString(), "time", "19:00", "format", "ONLINE",
			"host", "Enois"));
		MvcResult created = mockMvc.perform(post("/events")
				.header("X-Session-Token", host.token())
				.header("Idempotency-Key", "event-call-optout")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn();
		String eventId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(post("/events/" + eventId + "/calls")
				.header("X-Session-Token", host.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"AUDIO\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("EVENT_TAMO_JUNTO_DISABLED"));

		// A sala de outro evento nao vaza para este: ler a sala aberta daqui nao devolve nada.
		mockMvc.perform(get("/events/" + eventId + "/calls/open")
				.header("X-Session-Token", host.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").doesNotExist());
	}

	@Test
	void salaDeUmEncontroNaoApareceNoOutro() throws Exception {
		var host = auth("event-call-host4");
		String first = createEvent(host, "event-call-4a");
		String second = createEvent(host, "event-call-4b");
		startEventCall(host, first, "event-call-4a");

		mockMvc.perform(get("/events/" + second + "/calls/open")
				.header("X-Session-Token", host.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").doesNotExist());

		mockMvc.perform(get("/events/" + first + "/calls/open")
				.header("X-Session-Token", host.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.eventId").value(first));
	}

	@Test
	void avisoDeAoVivoChegaAQuemCriouEATemPresencaConfirmada() throws Exception {
		var host = auth("event-call-host5");
		var attending = auth("event-call-attending5");
		var stranger = auth("event-call-stranger5");
		String eventId = createEvent(host, "event-call-5");
		attend(attending, eventId, "event-call-5");

		startEventCall(host, eventId, "event-call-5");

		assertThat(callNotifications(attending)).anyMatch(item -> "CALL_EVENT_LIVE".equals(item.get("kind").asText()));
		// Quem não tem vínculo com o encontro não recebe nada: o aviso não é um broadcast.
		assertThat(callNotifications(stranger)).noneMatch(item -> "CALL_EVENT_LIVE".equals(item.get("kind").asText()));
	}

	// ------------------------------------------------------------------ apoio

	private TestAuthHelper.AuthSession auth(String who) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(who);
	}

	private String createEvent(TestAuthHelper.AuthSession who, String key) throws Exception {
		Map<String, Object> body = new HashMap<>(Map.of("title", "Encontro " + key,
			"date", LocalDate.now().plusMonths(2).toString(), "time", "19:00", "format", "ONLINE",
			"host", "Enois", "tamoJunto", true));
		MvcResult result = mockMvc.perform(post("/events")
				.header("X-Session-Token", who.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void attend(TestAuthHelper.AuthSession who, String eventId, String key) throws Exception {
		mockMvc.perform(post("/events/" + eventId + "/attendees")
				.header("X-Session-Token", who.token())
				.header("Idempotency-Key", "attend-" + key))
			.andExpect(status().isOk());
	}

	private JsonNode startEventCall(TestAuthHelper.AuthSession who, String eventId, String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/events/" + eventId + "/calls")
				.header("X-Session-Token", who.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"VIDEO\"}"))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode join(TestAuthHelper.AuthSession who, String callId) throws Exception {
		MvcResult result = mockMvc.perform(post("/calls/" + callId + "/join")
				.header("X-Session-Token", who.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private java.util.List<JsonNode> callNotifications(TestAuthHelper.AuthSession who) throws Exception {
		MvcResult result = mockMvc.perform(get("/notifications")
				.header("X-Session-Token", who.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode page = objectMapper.readTree(result.getResponse().getContentAsString());
		java.util.List<JsonNode> items = new java.util.ArrayList<>();
		page.get("items").forEach(items::add);
		return items;
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException failure) {
			throw new IllegalStateException("sem porta livre para o gateway falso", failure);
		}
	}

	private static FakeEpapoGateway startGateway(int port) {
		try {
			return new FakeEpapoGateway(port);
		}
		catch (IOException failure) {
			throw new IllegalStateException("gateway falso nao subiu", failure);
		}
	}
}
