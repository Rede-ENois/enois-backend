package com.socialcoisa.rede.calls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A ponte entre o Énóis e o servidor de reunião (Epapo), do ponto de vista do nosso lado: quem
 * pode entrar, o que a rota interna aceita e o que acontece quando o gateway recusa ou some.
 *
 * <p>O gateway aqui é um HTTP real numa porta local ({@link FakeEpapoGateway}); erros do gateway
 * viram erro explícito no app em vez de um painel apontando para lugar nenhum, e nada da resposta
 * do gateway (token, segredo) escapa para o corpo que o navegador recebe.
 */
@SpringBootTest(properties = {
		"rede.calls.jitsi.url=https://chamadas.example.test",
		"spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class CallGatewayBridgeTests {

	/** Porta livre escolhida pelo sistema; o gateway falso sobe nela. */
	private static final int PORTA = freePort();

	private static final FakeEpapoGateway GATEWAY = start(PORTA);

	/** Segredo descartável da ponte, gerado antes do contexto subir. */
	static final String PONTE = HexFormat.of().formatHex(new SecureRandom().generateSeed(24));

	static {
		System.setProperty("call.bridge.token", PONTE);
	}

	@DynamicPropertySource
	static void config(DynamicPropertyRegistry registry) {
		registry.add("rede.calls.epapo.gateway-url", () -> GATEWAY.url());
		registry.add("rede.calls.epapo.internal-token", () -> "epapo-interno-" + PONTE);
		registry.add("rede.calls.epapo.timeout-millis", () -> "2000");
		registry.add("rede.calls.gateway.authorize-token", () -> "${call.bridge.token}");
	}

	@AfterAll
	static void desliga() {
		GATEWAY.stop();
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	// ------------------------------------------------------------------ rota interna

	@Test
	void rotaInternaSemSegredoOuComSegredoErradoNaoRespondeNada() throws Exception {
		var a = auth("bridge-a1");
		var b = auth("bridge-b1");
		String callId = directCall(a, b, "bridge-a1-b1");

		mockMvc.perform(get("/calls/rooms/qualquer"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("GATEWAY_UNAUTHORIZED"));
		mockMvc.perform(get("/calls/rooms/qualquer").header("X-Enois-Authorize", PONTE + "x"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/calls/" + callId + "/gateway/entry/" + a.userId()))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/calls/" + callId + "/gateway/entry/" + a.userId())
				.header("X-Enois-Authorize", ""))
			.andExpect(status().isUnauthorized());

		// A pessoa cuja entrada foi negada não fica registrada como presente: recusa não abre sala
		// nem pela porta de trás. (Quem atendeu já aparece dentro, e é o único.)
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM call_participants WHERE call_id = ? AND user_id = ? AND state = 'JOINED'",
			Integer.class, callId, a.userId())).isZero();
	}

	@Test
	void rotaInternaConfereVinculoNaHoraEENaoVazaParaONavegador() throws Exception {
		var a = auth("bridge-a2");
		var b = auth("bridge-b2");
		var intruder = auth("bridge-x2");
		String callId = directCall(a, b, "bridge-a2-b2");
		String room = join(a, callId).get("room").asText();

		// Sala → chamada: o gateway só precisa disso para recusar sala desconhecida cedo.
		mockMvc.perform(get("/calls/rooms/" + room).header("X-Enois-Authorize", PONTE))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.callId").value(callId))
			.andExpect(jsonPath("$.state").value("ACTIVE"));
		mockMvc.perform(get("/calls/rooms/sala-que-nao-existe").header("X-Enois-Authorize", PONTE))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CALL_ROOM_NOT_FOUND"));

		// A entrada autorizada devolve exatamente o que o gateway precisa para assinar o token.
		mockMvc.perform(post("/calls/" + callId + "/gateway/entry/" + b.userId())
				.header("X-Enois-Authorize", PONTE))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.callId").value(callId))
			.andExpect(jsonPath("$.room").value(room))
			.andExpect(jsonPath("$.displayName").value("bridge-b2"))
			.andExpect(jsonPath("$.moderator").value(false));
		mockMvc.perform(post("/calls/" + callId + "/gateway/entry/" + a.userId())
				.header("X-Enois-Authorize", PONTE))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.moderator").value(true));

		// Fora da conversa não entra, nem com a ponte certa.
		mockMvc.perform(post("/calls/" + callId + "/gateway/entry/" + intruder.userId())
				.header("X-Enois-Authorize", PONTE))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_PARTICIPANT"));

		// Nenhuma das duas rotas fala com sessão de navegador, e nenhuma devolve token.
		String semSessao = mockMvc.perform(post("/calls/" + callId + "/gateway/entry/" + b.userId())
				.header("X-Enois-Authorize", PONTE))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(semSessao).doesNotContain("jwt").doesNotContain("token").doesNotContain("secret");
	}

	@Test
	void chamadaEncerradaNaoAutorizaEntradaPeloGateway() throws Exception {
		var a = auth("bridge-a3");
		var b = auth("bridge-b3");
		String callId = directCall(a, b, "bridge-a3-b3");
		join(a, callId);
		mockMvc.perform(post("/calls/" + callId + "/end").header("X-Session-Token", a.token()))
			.andExpect(status().isOk());

		mockMvc.perform(post("/calls/" + callId + "/gateway/entry/" + b.userId())
				.header("X-Enois-Authorize", PONTE))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CALL_ENDED"));
	}

	// ------------------------------------------------------------------ laço com o gateway

	@Test
	void entrarPedeTokenAoGatewaySóDepoisDaAutorizacaoDaqui() throws Exception {
		var a = auth("bridge-a4");
		var b = auth("bridge-b4");
		String callId = directCall(a, b, "bridge-a4-b4");
		int before = GATEWAY.tokenRequests();

		JsonNode join = join(b, callId);

		// O navegador recebe o token do Epapo, nunca a resposta crua do gateway.
		assertThat(join.get("jwt").asText()).startsWith("epapo-token-");
		assertThat(join.get("host").asText()).isEqualTo("https://chamadas.example.test");

		// O que sai daqui é só a identificação: o gateway é quem confere a entrada na rota interna
		// e descobre sala e nome sozinho. Nem sala, nem nome, nem segredo viajam neste pedido.
		FakeEpapoGateway.Received token = GATEWAY.last("/auth/token");
		assertThat(token).isNotNull();
		assertThat(token.header("X-Epapo-Internal")).isEqualTo("epapo-interno-" + PONTE);
		JsonNode payload = objectMapper.readTree(token.body());
		assertThat(payload.get("callId").asText()).isEqualTo(callId);
		assertThat(payload.get("userId").asText()).isEqualTo(b.userId());
		assertThat(token.body()).doesNotContain(join.get("room").asText()).doesNotContain("bridge-b4")
			.doesNotContain(PONTE);
		assertThat(GATEWAY.tokenRequests()).isEqualTo(before + 1);
	}

	@Test
	void gatewayRecusandoOVinculoViraErroExplicitoSemToken() throws Exception {
		var a = auth("bridge-a5");
		var b = auth("bridge-b5");
		String callId = directCall(a, b, "bridge-a5-b5");
		GATEWAY.respond("/auth/token", 403, "{\"error\":\"entry_denied\"}");
		try {
			mockMvc.perform(post("/calls/" + callId + "/join").header("X-Session-Token", b.token()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CALL_ENTRY_DENIED"));
		}
		finally {
			GATEWAY.reset("/auth/token");
		}
	}

	@Test
	void gatewaySemTokenValidoViraIndisponivelEmVezDeSalaAberta() throws Exception {
		var a = auth("bridge-a6");
		var b = auth("bridge-b6");
		String callId = directCall(a, b, "bridge-a6-b6");
		GATEWAY.respond("/auth/token", 500, "{\"error\":\"mint_failed\"}");
		try {
			mockMvc.perform(post("/calls/" + callId + "/join").header("X-Session-Token", b.token()))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("CALLS_UNAVAILABLE"));
		}
		finally {
			GATEWAY.reset("/auth/token");
		}
	}

	// ------------------------------------------------------------------ utilidades

	private TestAuthHelper.AuthSession auth(String who) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(who);
	}

	private String directCall(TestAuthHelper.AuthSession a, TestAuthHelper.AuthSession b, String key)
			throws Exception {
		String conversation = objectMapper.readTree(mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "dm-" + key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", b.userId()))))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).get("id").asText();
		String callId = objectMapper.readTree(mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", a.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"AUDIO\"}"))
			.andReturn().getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/calls/" + callId + "/answer").header("X-Session-Token", b.token()))
			.andExpect(status().isOk());
		return callId;
	}

	private JsonNode join(TestAuthHelper.AuthSession who, String callId) throws Exception {
		MvcResult result = mockMvc.perform(post("/calls/" + callId + "/join")
				.header("X-Session-Token", who.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException failure) {
			throw new IllegalStateException("sem porta livre para o gateway falso", failure);
		}
	}

	private static FakeEpapoGateway start(int port) {
		try {
			return new FakeEpapoGateway(port);
		}
		catch (IOException failure) {
			throw new IllegalStateException("gateway falso nao subiu", failure);
		}
	}
}
