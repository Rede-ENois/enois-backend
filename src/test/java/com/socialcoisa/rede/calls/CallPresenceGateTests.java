package com.socialcoisa.rede.calls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Chamada direta e estado de presença: quem escolheu não ser incomodado não recebe toque.
 *
 * <p>O que este teste tranca: "não perturbar" e "invisível" recusam a chamada direta antes dela
 * existir — sem tocar, sem notificação de chamada, sem sala aberta; a recusa é a mesma nos dois
 * casos, sem revelar o estado da outra pessoa; chamada de grupo não é afetada; e a chamada que já
 * estava tocando quando a pessoa mudou de estado é encerrada, em vez de continuar tocando.
 */
@SpringBootTest(properties = {
		"rede.calls.epapo.url=https://chamadas.example.test",
		"spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class CallPresenceGateTests {

	private static final int GATEWAY_PORT = freePort();

	static final FakeEpapoGateway GATEWAY = startGateway(GATEWAY_PORT);

	static final String BRIDGE = java.util.UUID.randomUUID() + "" + java.util.UUID.randomUUID();

	static {
		System.setProperty("call.presence.bridge", BRIDGE);
	}

	@DynamicPropertySource
	static void gateway(DynamicPropertyRegistry registry) {
		registry.add("rede.calls.epapo.gateway-url", () -> GATEWAY.url());
		registry.add("rede.calls.epapo.internal-token", () -> "epapo-" + BRIDGE);
		registry.add("rede.calls.gateway.authorize-token", () -> "${call.presence.bridge}");
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
	void naoPerturbarNaoRecebeChamadaDireta() throws Exception {
		var a = auth("presence-call-a1");
		var b = auth("presence-call-b1");
		String conversation = directConversation(a, b, "presence-call-a1-b1");
		setPresence(b, "DO_NOT_DISTURB");

		mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", a.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"AUDIO\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CALL_RECIPIENT_UNAVAILABLE"));

		// Nada de chamada criada, nada de notificação de toque.
		assertThat(openCalls(a)).isZero();
		assertThat(callNotifications(b)).isZero();
	}

	@Test
	void invisivelNaoRecebeChamadaDiretaEARecusaNaoDizQualEstadoEO() throws Exception {
		var a = auth("presence-call-a2");
		var b = auth("presence-call-b2");
		String conversation = directConversation(a, b, "presence-call-a2-b2");
		setPresence(b, "INVISIBLE");

		String message = mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", a.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"AUDIO\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CALL_RECIPIENT_UNAVAILABLE"))
			.andReturn().getResponse().getContentAsString();

		// A mesma resposta para "não perturbar" e "invisível": quem liga não descobre o estado.
		assertThat(message).doesNotContain("DO_NOT_DISTURB").doesNotContain("INVISIBLE")
			.doesNotContain("perturb").doesNotContain("invis");
		assertThat(openCalls(a)).isZero();
		assertThat(callNotifications(b)).isZero();
	}

	@Test
	void quemEstaAusenteOuOcupadoAindaRecebeChamadaDireta() throws Exception {
		var a = auth("presence-call-a3");
		var b = auth("presence-call-b3");
		String conversation = directConversation(a, b, "presence-call-a3-b3");

		setPresence(b, "AWAY");
		mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", a.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"AUDIO\"}"))
			.andExpect(status().isOk());

		setPresence(b, "AVAILABLE");
		assertThat(callNotifications(b)).isPositive();
	}

	@Test
	void chamadaQueEstavaTocandoTerminaQuandoAPessoaFicaIndisponivel() throws Exception {
		var a = auth("presence-call-a4");
		var b = auth("presence-call-a4b");
		String conversation = directConversation(a, b, "presence-call-a4-b4");

		JsonNode call = body(mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", a.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"AUDIO\"}"))
			.andExpect(status().isOk())
			.andReturn());
		String callId = call.get("id").asText();
		assertThat(call.get("state").asText()).isEqualTo("RINGING");

		// A pessoa muda de estado com a chamada já tocando: o toque não pode continuar.
		setPresence(b, "DO_NOT_DISTURB");

		mockMvc.perform(get("/calls/" + callId).header("X-Session-Token", a.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("DECLINED"));
	}

	// ------------------------------------------------------------------ utilidades

	private TestAuthHelper.AuthSession auth(String who) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(who);
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
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void setPresence(TestAuthHelper.AuthSession who, String status) throws Exception {
		mockMvc.perform(put("/me/presence")
				.header("X-Session-Token", who.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"" + status + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(status));
	}

	private int openCalls(TestAuthHelper.AuthSession who) throws Exception {
		return objectMapper.readTree(mockMvc.perform(get("/calls/open").header("X-Session-Token", who.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).size();
	}

	private int callNotifications(TestAuthHelper.AuthSession who) throws Exception {
		String content = mockMvc.perform(get("/notifications").header("X-Session-Token", who.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		int count = 0;
		for (JsonNode item : objectMapper.readTree(content).get("items")) {
			if (item.get("kind").asText().startsWith("CALL_")) {
				count++;
			}
		}
		return count;
	}

	private JsonNode body(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private static int freePort() {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (java.io.IOException failure) {
			throw new IllegalStateException("sem porta livre para o gateway falso", failure);
		}
	}

	private static FakeEpapoGateway startGateway(int port) {
		try {
			return new FakeEpapoGateway(port);
		}
		catch (java.io.IOException failure) {
			throw new IllegalStateException("gateway falso nao subiu", failure);
		}
	}
}
