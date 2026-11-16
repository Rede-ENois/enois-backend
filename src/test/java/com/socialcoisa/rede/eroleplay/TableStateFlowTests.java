package com.socialcoisa.rede.eroleplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

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
 * O estado vivo da mesa, de ponta a ponta: ordem de fala, vez (do mestre e do jogador), cena,
 * persistência (o estado sobrevive à leitura seguinte) e o mesmo Idempotency-Key não grava duas.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TableStateFlowTests {

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
					"description", "campanha de teste do estado de mesa",
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
				.content(objectMapper.writeValueAsString(Map.of("name", "Mesa de Estado", "open", true))))
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

	private JsonNode postJson(TestAuthHelper.AuthSession who, String url, String idemKey, Map<String, Object> payload)
			throws Exception {
		MvcResult result = mockMvc.perform(post(url)
				.header("X-Session-Token", who.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(payload)))
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode state(TestAuthHelper.AuthSession who, String campaignId, String tableId) throws Exception {
		MvcResult result = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/tables/" + tableId + "/state")
				.header("X-Session-Token", who.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	@Test
	void estadoNasceVazioEPersisteOrdemVezECena() throws Exception {
		var master = authHelper().authenticate("f6-estado-mestre");
		String campaignId = createCampaign(master, "Estado da Mesa", "f6-create-estado");
		String tableId = createTable(master, campaignId, "f6-table-estado");

		JsonNode vazio = state(master, campaignId, tableId);
		assertThat(vazio.get("tableId").asText()).isEqualTo(tableId);
		assertThat(vazio.get("turnOrder").isArray()).isTrue();
		assertThat(vazio.get("turnOrder").size()).isZero();
		assertThat(vazio.get("activeTurnUserId").isNull()).isTrue();

		String base = "/roleplay/campaigns/" + campaignId + "/tables/" + tableId;
		postJson(master, base + "/state/turn-order", "f6-ordem-1",
			Map.of("order", java.util.List.of(master.userId())));
		postJson(master, base + "/state/turn", "f6-vez-1", Map.of("userId", master.userId()));
		postJson(master, base + "/state/scene", "f6-cena-1",
			Map.of("scene", "A taverna do javali ferido, noite de tempestade"));

		JsonNode depois = state(master, campaignId, tableId);
		assertThat(depois.get("turnOrder").get(0).asText()).isEqualTo(master.userId());
		assertThat(depois.get("activeTurnUserId").asText()).isEqualTo(master.userId());
		assertThat(depois.get("turnStartedAt").isNull()).isFalse();
		assertThat(depois.get("scene").asText()).isEqualTo("A taverna do javali ferido, noite de tempestade");
	}

	@Test
	void jogadorPassaAVezParaSiMesmoSemSerMestre() throws Exception {
		var master = authHelper().authenticate("f6-vez-mestre");
		var player = authHelper().authenticate("f6-vez-jogador");
		String campaignId = createCampaign(master, "Vez da Mesa", "f6-create-vez");
		String tableId = createTable(master, campaignId, "f6-table-vez");
		join(player, campaignId, "f6-join-vez");

		String base = "/roleplay/campaigns/" + campaignId + "/tables/" + tableId;
		postJson(master, base + "/state/turn-order", "f6-vez-ordem",
			Map.of("order", java.util.List.of(master.userId(), player.userId())));
		postJson(master, base + "/state/turn", "f6-vez-mestre-da",
			Map.of("userId", master.userId()));

		// O jogador assume a própria vez — sem ser mestre (a resposta é o estado, não um erro).
		JsonNode assumiu = postJson(player, base + "/state/turn", "f6-vez-jogador-da", Map.of());
		assertThat(assumiu.has("code")).isFalse();
		assertThat(assumiu.get("activeTurnUserId").asText()).isEqualTo(player.userId());

		// O jogador não entrega a vez de outra pessoa nem inicia a vez de um terceiro.
		var terceiro = authHelper().authenticate("f6-vez-terceiro");
		join(terceiro, campaignId, "f6-join-vez-3");
		MvcResult negado = mockMvc.perform(post(base + "/state/turn")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "f6-vez-jogador-entrega")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", terceiro.userId()))))
			.andReturn();
		assertThat(negado.getResponse().getStatus()).isEqualTo(403);
		assertThat(negado.getResponse().getContentAsString()).contains("FORBIDDEN");
	}

	@Test
	void mesmoIdempotencyKeyNaoRegravaOEstado() throws Exception {
		var master = authHelper().authenticate("f6-idem-mestre");
		String campaignId = createCampaign(master, "Estado Idem", "f6-create-idem");
		String tableId = createTable(master, campaignId, "f6-table-idem");

		String base = "/roleplay/campaigns/" + campaignId + "/tables/" + tableId;
		postJson(master, base + "/state/scene", "f6-idem-cena", Map.of("scene", "cena gravada uma vez"));
		postJson(master, base + "/state/scene", "f6-idem-cena", Map.of("scene", "cena gravada uma vez"));

		JsonNode estado = state(master, campaignId, tableId);
		assertThat(estado.get("scene").asText()).isEqualTo("cena gravada uma vez");
		assertThat(estado.get("updatedAt").isNull()).isFalse();
	}

	@Test
	void tokenDeCenarioEDePersonagemSeguemQuemPodeMover() throws Exception {
		var master = authHelper().authenticate("f6-token-mestre");
		var player = authHelper().authenticate("f6-token-jogador");
		String campaignId = createCampaign(master, "Tokens da Mesa", "f6-create-tokens");
		String tableId = createTable(master, campaignId, "f6-table-tokens");
		join(player, campaignId, "f6-join-tokens");

		String base = "/roleplay/campaigns/" + campaignId + "/tables/" + tableId;

		// Cenário: peça do mestre.
		JsonNode cenario = postJson(master, base + "/tokens", "f6-token-cenario",
			Map.of("label", "porta trancada", "color", "#808080", "posX", 2, "posY", 3));
		assertThat(cenario.get("ownerId").isNull()).isTrue();
		String cenarioId = cenario.get("id").asText();

		// Personagem: o jogador entra com o dele.
		JsonNode personagem = postJson(player, base + "/tokens", "f6-token-personagem",
			Map.of("ownerId", player.userId(), "label", "Elena", "color", "#00FF00", "posX", -4, "posY", 1));
		assertThat(personagem.get("ownerId").asText()).isEqualTo(player.userId());
		String personagemId = personagem.get("id").asText();

		// O jogador move o dele; o cenário não.
		JsonNode movido = postJson(player, base + "/tokens/" + personagemId + "/move", "f6-token-move-proprio",
			Map.of("posX", 5, "posY", 5));
		assertThat(movido.get("posX").asInt()).isEqualTo(5);
		MvcResult negado = mockMvc.perform(post(base + "/tokens/" + cenarioId + "/move")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "f6-token-move-cenario")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("posX", 9, "posY", 9))))
			.andReturn();
		assertThat(negado.getResponse().getStatus()).isEqualTo(403);

		// O mestre move qualquer um, e a lista mostra os dois.
		JsonNode movidoPeloMestre = postJson(master, base + "/tokens/" + personagemId + "/move",
			"f6-token-move-mestre", Map.of("posX", 7, "posY", -2));
		assertThat(movidoPeloMestre.get("posY").asInt()).isEqualTo(-2);

		MvcResult lista = mockMvc.perform(get(base + "/tokens")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode tokens = objectMapper.readTree(lista.getResponse().getContentAsString());
		assertThat(tokens.size()).isEqualTo(2);

		// O dono remove o dele; o mestre remove o cenário.
		postJson(player, base + "/tokens/" + personagemId + "/remove", "f6-token-remove-proprio", Map.of());
		postJson(master, base + "/tokens/" + cenarioId + "/remove", "f6-token-remove-cenario", Map.of());
		MvcResult vazia = mockMvc.perform(get(base + "/tokens")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(objectMapper.readTree(vazia.getResponse().getContentAsString()).size()).isZero();
	}

	@Test
	void entradaInvalidaDeTokenVolta400SemGravar() throws Exception {
		var master = authHelper().authenticate("f6-token-invalido");
		String campaignId = createCampaign(master, "Tokens Invalidos", "f6-create-invalido");
		String tableId = createTable(master, campaignId, "f6-table-invalido");
		String base = "/roleplay/campaigns/" + campaignId + "/tables/" + tableId;

		MvcResult semCor = mockMvc.perform(post(base + "/tokens")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f6-token-sem-cor")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("label", "fantasma", "posX", 0, "posY", 0))))
			.andReturn();
		assertThat(semCor.getResponse().getStatus()).isEqualTo(400);
		assertThat(semCor.getResponse().getContentAsString()).contains("TOKEN_COLOR_INVALID");

		MvcResult fora = mockMvc.perform(post(base + "/tokens")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f6-token-fora-do-grid")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("label", "gigante", "color", "#FF0000",
					"posX", 99_999, "posY", 0))))
			.andReturn();
		assertThat(fora.getResponse().getStatus()).isEqualTo(400);
		assertThat(fora.getResponse().getContentAsString()).contains("TOKEN_POSITION_INVALID");

		MvcResult lista = mockMvc.perform(get(base + "/tokens")
				.header("X-Session-Token", master.token()))
			.andReturn();
		assertThat(objectMapper.readTree(lista.getResponse().getContentAsString()).size()).isZero();
	}
}
