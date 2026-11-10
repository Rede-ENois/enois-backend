package com.socialcoisa.rede.calls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Aviso de chamada vai para quem a chamada é, e para mais ninguém.
 *
 * <p>A regra vale nas três formas de chamada — 1:1, "Tamo junto" do grupo e sala do encontro — e em
 * todos os canais: a notificação gravada, o stream do navegador e a própria API. Cada teste abre
 * um stream para uma pessoa de fora da chamada e confere, no banco e no stream, que nada chegou.
 *
 * <p>Também tranca a lista de destinatários da chamada: ela é a de quem foi convidado mais quem
 * entrou, e não "todo mundo que faz parte do grupo agora". Quem entra depois de a chamada começar
 * não recebe o aviso de que ela terminou.
 */
@SpringBootTest(properties = {
		"rede.calls.epapo.url=https://chamadas.example.test",
		"spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class CallNotificationIsolationTests {

	private static final int GATEWAY_PORT = freePort();

	static final FakeEpapoGateway GATEWAY = startGateway(GATEWAY_PORT);

	static final String BRIDGE = java.util.UUID.randomUUID() + "" + java.util.UUID.randomUUID();

	static {
		System.setProperty("call.isolation.bridge", BRIDGE);
	}

	@DynamicPropertySource
	static void gateway(DynamicPropertyRegistry registry) {
		registry.add("rede.calls.epapo.gateway-url", () -> GATEWAY.url());
		registry.add("rede.calls.epapo.internal-token", () -> "epapo-" + BRIDGE);
		registry.add("rede.calls.gateway.authorize-token", () -> "${call.isolation.bridge}");
	}

	@AfterAll
	static void shutGateway() {
		GATEWAY.stop();
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	// ------------------------------------------------------------------ 1:1

	@Test
	void chamadaDiretaSoAvisaQuemFoiChamado() throws Exception {
		var a = auth("isolation-dm-a");
		var b = auth("isolation-dm-b");
		var outsider = auth("isolation-dm-x");
		String conversation = directConversation(a, b, "isolation-dm-a-b");

		MvcResult outsiderStream = openStream(outsider);
		String callId = body(mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", a.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"AUDIO\"}"))
			.andExpect(status().isOk())
			.andReturn()).get("id").asText();

		// Quem foi chamado recebe o toque; o de fora não recebe nada, nem no banco nem no stream.
		assertThat(count(b, "CALL_INCOMING")).isEqualTo(1);
		assertThat(callNotifications(outsider)).isZero();
		assertThat(streamOf(outsiderStream)).doesNotContain(callId);

		// Quem ligou desiste (a chamada ainda está tocando): cancelar não avisa ninguém além do que
		// já foi chamado, e o de fora continua sem receber nada.
		mockMvc.perform(post("/calls/" + callId + "/cancel").header("X-Session-Token", a.token()))
			.andExpect(status().isOk());
		assertThat(callNotifications(outsider)).isZero();
		assertThat(streamOf(outsiderStream)).doesNotContain(callId);
	}

	@Test
	void quemFoiChamadoERecusouNaoLevaOAvisoDeEncerrada() throws Exception {
		var a = auth("isolation-dm-c");
		var b = auth("isolation-dm-d");
		String conversation = directConversation(a, b, "isolation-dm-c-d");
		String callId = body(mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", a.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"VIDEO\"}"))
			.andExpect(status().isOk())
			.andReturn()).get("id").asText();

		mockMvc.perform(post("/calls/" + callId + "/decline").header("X-Session-Token", b.token()))
			.andExpect(status().isOk());

		// Quem recusou já decidiu: o "chamada encerrada" vai para quem ligou, e não para quem recusou.
		assertThat(count(a, "CALL_ENDED")).isEqualTo(1);
		assertThat(count(b, "CALL_ENDED")).isZero();
	}

	// ------------------------------------------------------------------ grupo

	@Test
	void tamoJuntoDoGrupoNaoAlcancaQuemEstadeFora() throws Exception {
		var owner = auth("isolation-group-owner");
		var member = auth("isolation-group-member");
		var outsider = auth("isolation-group-outsider");
		String groupId = createGroup(owner, "Isolamento do tamo junto", "isolation-group");
		joinGroup(member, groupId, "isolation-group-member");

		MvcResult outsiderStream = openStream(outsider);
		String callId = body(mockMvc.perform(post("/groups/" + groupId + "/calls")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"AUDIO\"}"))
			.andExpect(status().isOk())
			.andReturn()).get("id").asText();

		assertThat(count(member, "CALL_GROUP_LIVE")).isEqualTo(1);
		assertThat(callNotifications(outsider)).isZero();
		assertThat(streamOf(outsiderStream)).doesNotContain(callId);

		// A sala do grupo também não abre a tela de quem não é membro.
		mockMvc.perform(get("/calls/" + callId).header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/calls/" + callId + "/join").header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void quemEntraNoGrupoDepoisNaoHerdaOAvisoDaChamada() throws Exception {
		var owner = auth("isolation-late-owner");
		var lateMember = auth("isolation-late-member");
		String groupId = createGroup(owner, "Isolamento de quem chega depois", "isolation-late");
		String callId = body(mockMvc.perform(post("/groups/" + groupId + "/calls")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"AUDIO\"}"))
			.andExpect(status().isOk())
			.andReturn()).get("id").asText();

		joinGroup(lateMember, groupId, "isolation-late-member");

		// A lista de destinatários é a da chamada, não a do grupo de agora: quem chegou depois não
		// recebe o aviso de encerramento de uma conversa em que nunca esteve.
		assertThat(count(lateMember, "CALL_GROUP_LIVE")).isZero();
		mockMvc.perform(post("/calls/" + callId + "/end").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());
		assertThat(count(lateMember, "CALL_ENDED")).isZero();
	}

	// ------------------------------------------------------------------ encontro

	@Test
	void salaDoEncontroNaoAlcancaQuemNaoTemVinculoComOEncontro() throws Exception {
		var host = auth("isolation-event-host");
		var stranger = auth("isolation-event-stranger");
		String eventId = createEvent(host, "isolation-event");

		MvcResult strangerStream = openStream(stranger);
		String callId = body(mockMvc.perform(post("/events/" + eventId + "/calls")
				.header("X-Session-Token", host.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"VIDEO\"}"))
			.andExpect(status().isOk())
			.andReturn()).get("id").asText();

		assertThat(callNotifications(stranger)).isZero();
		assertThat(streamOf(strangerStream)).doesNotContain(callId);
		// Entrar continua aberto para quem tem sessão — o que não é empecilho é receber aviso.
		mockMvc.perform(post("/calls/" + callId + "/join").header("X-Session-Token", stranger.token()))
			.andExpect(status().isOk());
	}

	// ------------------------------------------------------------------ apoio

	private TestAuthHelper.AuthSession auth(String who) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(who);
	}

	/** Quantas notificações deste tipo a pessoa tem, contadas só nas linhas dela. */
	private int count(TestAuthHelper.AuthSession who, String kind) {
		return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND kind = ?",
			Integer.class, who.userId(), kind);
	}

	/** Notificações de chamada da pessoa, só pelas linhas dela — nunca por uma listagem global. */
	private int callNotifications(TestAuthHelper.AuthSession who) {
		return jdbc.queryForObject(
			"SELECT count(*) FROM notifications WHERE user_id = ? AND kind IN ('CALL_INCOMING', 'CALL_ENDED', "
				+ "'CALL_MISSED', 'CALL_GROUP_LIVE', 'CALL_EVENT_LIVE')", Integer.class, who.userId());
	}

	private MvcResult openStream(TestAuthHelper.AuthSession who) throws Exception {
		return mockMvc.perform(get("/conversations/stream")
				.header("X-Session-Token", who.token())
				.accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(request().asyncStarted())
			.andReturn();
	}

	private String streamOf(MvcResult stream) throws Exception {
		return stream.getResponse().getContentAsString();
	}

	private String directConversation(TestAuthHelper.AuthSession a, TestAuthHelper.AuthSession b, String key)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "dm-" + key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", b.userId()))))
			.andExpect(status().isOk())
			.andReturn();
		return body(result).get("id").asText();
	}

	private String createGroup(TestAuthHelper.AuthSession owner, String name, String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/groups")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "description", name,
					"joinMode", "OPEN"))))
			.andExpect(status().isCreated())
			.andReturn();
		return body(result).get("id").asText();
	}

	private void joinGroup(TestAuthHelper.AuthSession who, String groupId, String key) throws Exception {
		mockMvc.perform(post("/groups/" + groupId + "/join")
				.header("X-Session-Token", who.token())
				.header("Idempotency-Key", key))
			.andExpect(status().isOk());
	}

	private String createEvent(TestAuthHelper.AuthSession who, String key) throws Exception {
		Map<String, Object> payload = new HashMap<>(Map.of("title", "Encontro " + key,
			"date", java.time.LocalDate.now().plusMonths(2).toString(), "time", "19:00", "format", "ONLINE",
			"host", "Enois",
			// A sala do encontro agora e escolha de quem cria: estes cenarios testam o aviso, nao o opt-in.
			"tamoJunto", true));
		MvcResult result = mockMvc.perform(post("/events")
				.header("X-Session-Token", who.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(payload)))
			.andExpect(status().isCreated())
			.andReturn();
		return body(result).get("id").asText();
	}

	private JsonNode body(MvcResult result) throws Exception {
		String content = result.getResponse().getContentAsString();
		return content == null || content.isBlank() ? objectMapper.createObjectNode()
			: objectMapper.readTree(content);
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
