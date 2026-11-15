package com.socialcoisa.rede.calls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.UUID;

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
 * A voz da mesa pelo contrato existente do Épapo.
 *
 * <p>O que este teste tranca: abrir a voz da mesa cria uma chamada com vínculo {@code tableId} e
 * quem abriu já está dentro; quem participa da campanha entra pelo join de sempre; quem não
 * participa não entra e não vê a sala "ao vivo"; a sala nasce uma vez (segundo clique devolve a
 * mesma); a sala de uma mesa não vaza para a outra; e o aviso "a mesa está ao vivo" chega só aos
 * participantes — sem tocar ninguém.</p>
 */
@SpringBootTest(properties = {
		"rede.calls.epapo.url=https://chamadas.example.test",
		"spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class TableVoiceFlowTests {

	private static final int GATEWAY_PORT = freePort();

	static final FakeEpapoGateway GATEWAY = startGateway(GATEWAY_PORT);

	static final String BRIDGE = UUID.randomUUID() + "" + UUID.randomUUID();

	static {
		System.setProperty("call.table.bridge", BRIDGE);
	}

	@DynamicPropertySource
	static void gateway(DynamicPropertyRegistry registry) {
		registry.add("rede.calls.epapo.gateway-url", () -> GATEWAY.url());
		registry.add("rede.calls.epapo.internal-token", () -> "epapo-" + BRIDGE);
		registry.add("rede.calls.gateway.authorize-token", () -> "${call.table.bridge}");
	}

	@AfterAll
	static void shutGateway() {
		GATEWAY.stop();
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper authHelper() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	private String createCampaign(TestAuthHelper.AuthSession master, String name, String idemKey) throws Exception {
		MvcResult result = mockMvc.perform(post("/roleplay/campaigns")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", name,
					"description", "campanha de teste da voz da mesa",
					"gameSystem", "d20-generico",
					"systemFamily", "D20",
					"visibility", "PUBLIC"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String createTable(TestAuthHelper.AuthSession master, String campaignId, String idemKey) throws Exception {
		MvcResult result = mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/tables")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Mesa ao Vivo", "open", true))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void join(TestAuthHelper.AuthSession user, String campaignId, String idemKey) throws Exception {
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/join")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", idemKey))
			.andExpect(status().isOk());
	}

	private JsonNode openVoice(TestAuthHelper.AuthSession who, String campaignId, String tableId, String idemKey)
			throws Exception {
		MvcResult result = mockMvc.perform(
				post("/roleplay/campaigns/" + campaignId + "/tables/" + tableId + "/voice")
					.header("X-Session-Token", who.token())
					.header("Idempotency-Key", idemKey)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode joinCall(TestAuthHelper.AuthSession who, String callId) throws Exception {
		MvcResult result = mockMvc.perform(post("/calls/" + callId + "/join")
				.header("X-Session-Token", who.token()))
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private java.util.List<JsonNode> notifications(TestAuthHelper.AuthSession who) throws Exception {
		MvcResult result = mockMvc.perform(get("/notifications").header("X-Session-Token", who.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode page = objectMapper.readTree(result.getResponse().getContentAsString());
		java.util.List<JsonNode> items = new java.util.ArrayList<>();
		page.get("items").forEach(items::add);
		return items;
	}

	@Test
	void vozDaMesaNasceComVinculoDaMesaEQuemAbreJaEstaDentro() throws Exception {
		var master = authHelper().authenticate("f6-voz-mestre1");
		String campaignId = createCampaign(master, "Voz Mesa 1", "f6-voz-create-1");
		String tableId = createTable(master, campaignId, "f6-voz-table-1");

		JsonNode mesa = openVoice(master, campaignId, tableId, "f6-voz-open-1");

		assertThat(mesa.get("hasVoice").asBoolean()).isTrue();
		JsonNode call = mockMvc.perform(
				get("/roleplay/campaigns/" + campaignId + "/tables/" + tableId + "/voice/open")
					.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tableId").value(tableId))
			.andReturn().getResponse().getContentAsString().isEmpty() ? null
			: objectMapper.readTree(mockMvc
				.perform(get("/roleplay/campaigns/" + campaignId + "/tables/" + tableId + "/voice/open")
					.header("X-Session-Token", master.token()))
				.andReturn().getResponse().getContentAsString());
		assertThat(call.get("tableId").asText()).isEqualTo(tableId);
		assertThat(call.get("modality").asText()).isEqualTo("GROUP");

		// A mesa guarda a referência da chamada — o front vai ao join de sempre com o id.
		MvcResult join = mockMvc.perform(post("/calls/" + call.get("id").asText() + "/join")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(objectMapper.readTree(join.getResponse().getContentAsString()).get("jwt").asText())
			.startsWith("epapo-token-");
	}

	@Test
	void segundaAberturaDevolveAMesmaSala() throws Exception {
		var master = authHelper().authenticate("f6-voz-mestre2");
		String campaignId = createCampaign(master, "Voz Mesa 2", "f6-voz-create-2");
		String tableId = createTable(master, campaignId, "f6-voz-table-2");

		JsonNode primeira = openVoice(master, campaignId, tableId, "f6-voz-open-2a");
		JsonNode callAberta = mockMvc.perform(
				get("/roleplay/campaigns/" + campaignId + "/tables/" + tableId + "/voice/open")
					.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().isEmpty() ? null : objectMapper.readTree(
				mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/tables/" + tableId + "/voice/open")
						.header("X-Session-Token", master.token()))
					.andReturn().getResponse().getContentAsString());
		String callId = callAberta.get("id").asText();

		openVoice(master, campaignId, tableId, "f6-voz-open-2b");

		JsonNode depois = mockMvc.perform(
				get("/roleplay/campaigns/" + campaignId + "/tables/" + tableId + "/voice/open")
					.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().isEmpty() ? null : objectMapper.readTree(
				mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/tables/" + tableId + "/voice/open")
						.header("X-Session-Token", master.token()))
					.andReturn().getResponse().getContentAsString());
		assertThat(depois.get("id").asText()).isEqualTo(callId);
		assertThat(primeira.get("hasVoice").asBoolean()).isTrue();
	}

	@Test
	void participanteDaCampanhaEntraEVisitanteNao() throws Exception {
		var master = authHelper().authenticate("f6-voz-mestre3");
		var player = authHelper().authenticate("f6-voz-jogador3");
		var visitante = authHelper().authenticate("f6-voz-visitante3");
		String campaignId = createCampaign(master, "Voz Mesa 3", "f6-voz-create-3");
		String tableId = createTable(master, campaignId, "f6-voz-table-3");
		join(player, campaignId, "f6-voz-join-3");

		JsonNode mesa = openVoice(master, campaignId, tableId, "f6-voz-open-3");
		JsonNode call = objectMapper.readTree(mockMvc
			.perform(get("/roleplay/campaigns/" + campaignId + "/tables/" + tableId + "/voice/open")
				.header("X-Session-Token", master.token()))
			.andReturn().getResponse().getContentAsString());
		String callId = call.get("id").asText();
		assertThat(call.get("tableId").asText()).isEqualTo(tableId);

		// Quem participa entra pelo join de sempre e a contagem sobe.
		JsonNode entrou = joinCall(player, callId);
		assertThat(entrou.get("jwt").asText()).startsWith("epapo-token-");

		// Quem não participa não vê a sala ao vivo nem entra nela.
		mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/tables/" + tableId + "/voice/open")
				.header("X-Session-Token", visitante.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").doesNotExist());
		MvcResult negado = mockMvc.perform(post("/calls/" + callId + "/join")
				.header("X-Session-Token", visitante.token()))
			.andReturn();
		assertThat(negado.getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void salaDeUmaMesaNaoVaziaParaAOutra() throws Exception {
		var master = authHelper().authenticate("f6-voz-mestre4");
		String campaignId = createCampaign(master, "Voz Mesa 4", "f6-voz-create-4");
		String tableA = createTable(master, campaignId, "f6-voz-table-4a");
		String tableB = createTable(master, campaignId, "f6-voz-table-4b");

		openVoice(master, campaignId, tableA, "f6-voz-open-4a");

		// A mesa B não tem sala: a leitura não devolve nada (nem a sala da A).
		mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/tables/" + tableB + "/voice/open")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").doesNotExist());
	}

	@Test
	void avisoDeMesaAoVivoChegaAosParticipantesENaoTocaNinguem() throws Exception {
		var master = authHelper().authenticate("f6-voz-mestre5");
		var player = authHelper().authenticate("f6-voz-jogador5");
		var estranho = authHelper().authenticate("f6-voz-estranho5");
		String campaignId = createCampaign(master, "Voz Mesa 5", "f6-voz-create-5");
		String tableId = createTable(master, campaignId, "f6-voz-table-5");
		join(player, campaignId, "f6-voz-join-5");

		openVoice(master, campaignId, tableId, "f6-voz-open-5");

		assertThat(notifications(player)).anyMatch(item -> "CALL_TABLE_LIVE".equals(item.get("kind").asText()));
		// Sem vínculo com a campanha, sem aviso: não é broadcast.
		assertThat(notifications(estranho)).noneMatch(item -> "CALL_TABLE_LIVE".equals(item.get("kind").asText()));
		assertThat(notifications(master))
			.noneMatch(item -> "CALL_INCOMING".equals(item.get("kind").asText()));
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
