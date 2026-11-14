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
 * O motor de dados, de ponta a ponta.
 *
 * <p>O que este teste tranca:</p>
 *
 * <ul>
 * <li>rolar grava: o resultado vem do servidor, com cada dado e a soma conferindo;</li>
 * <li>o registro sobrevive à leitura — o que o histórico devolve é o que foi sorteado;</li>
 * <li>a rolagem fechada não aparece no histórico de outro jogador nem na leitura por id;</li>
 * <li>o mestre pede, o jogador responde, e o pedido respondido aponta para a rolagem que o
 *     respondeu;</li>
 * <li>responder duas vezes é recusado — e recusado com 409, não com uma segunda rolagem;</li>
 * <li>o jogador não pode trocar a expressão pedida pelo mestre;</li>
 * <li>o mesmo Idempotency-Key não rola duas vezes.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DiceFlowTests {

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
					"description", "campanha de teste de dados",
					"gameSystem", "d20-generico",
					"systemFamily", "D20",
					"visibility", "PUBLIC"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void join(TestAuthHelper.AuthSession user, String campaignId) throws Exception {
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/join")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "f4-join-" + campaignId + "-" + user.userId()))
			.andExpect(status().isOk());
	}

	private MvcResult roll(TestAuthHelper.AuthSession user, String campaignId, String idemKey, String expression,
			String label, String visibility) throws Exception {
		return mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/rolls")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"expression", expression,
					"label", label == null ? "" : label,
					"visibility", visibility == null ? "" : visibility))))
			.andReturn();
	}

	private MvcResult requestRoll(TestAuthHelper.AuthSession master, String campaignId, String idemKey,
			String playerId, String expression, String visibility) throws Exception {
		return mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/rolls/requests")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"playerId", playerId,
					"expression", expression,
					"label", "percepcao",
					"reason", "ouvir a porta",
					"visibility", visibility == null ? "" : visibility))))
			.andReturn();
	}

	private MvcResult answer(TestAuthHelper.AuthSession player, String campaignId, String requestId,
			String idemKey, String label) throws Exception {
		return mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/rolls/requests/" + requestId + "/answer")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("label", label == null ? "" : label))))
			.andReturn();
	}

	private JsonNode body(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	@Test
	void rolarGravaOResultadoComCadaDado() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4-rolar-mestre");
		String campaignId = createCampaign(master, "Rolagem Simples", "f4-create-rolar");

		MvcResult rolled = roll(master, campaignId, "f4-rolar-1", "2d6+3", "ataque", "CAMPAIGN");
		assertThat(rolled.getResponse().getStatus()).isEqualTo(201);

		JsonNode roll = body(rolled);
		assertThat(roll.get("expression").asText()).isEqualTo("2d6+3");
		assertThat(roll.get("dice")).hasSize(2);
		assertThat(roll.get("rollerId").asText()).isEqualTo(master.userId());
		assertThat(roll.get("visibility").asText()).isEqualTo("CAMPAIGN");

		long sum = 0;
		for (JsonNode die : roll.get("dice")) {
			int value = die.asInt();
			assertThat(value).isBetween(1, 6);
			sum += value;
		}
		assertThat(roll.get("total").asLong()).isEqualTo(sum + 3);
	}

	@Test
	void oHistoricoDevolveOMesmoQueFoiSorteado() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4-historico-mestre");
		String campaignId = createCampaign(master, "Historico", "f4-create-historico");

		JsonNode rolled = body(roll(master, campaignId, "f4-historico-1", "1d20", "percepcao", "CAMPAIGN"));
		String rollId = rolled.get("id").asText();

		JsonNode history = body(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/rolls")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn());
		assertThat(history).isNotEmpty();
		JsonNode found = null;
		for (JsonNode candidate : history) {
			if (rollId.equals(candidate.get("id").asText())) {
				found = candidate;
			}
		}
		assertThat(found).isNotNull();
		assertThat(found.get("total").asLong()).isEqualTo(rolled.get("total").asLong());
		assertThat(found.get("dice").get(0).asInt()).isEqualTo(rolled.get("dice").get(0).asInt());

		JsonNode one = body(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/rolls/" + rollId)
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn());
		assertThat(one.get("total").asLong()).isEqualTo(rolled.get("total").asLong());
	}

	@Test
	void aRolagemFechadaNaoApareceParaOutroJogador() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4-fechada-mestre");
		String campaignId = createCampaign(master, "Rolagem Fechada", "f4-create-fechada");

		var player = authHelper().authenticate("eroleplay-f4-fechada-jogador");
		var outro = authHelper().authenticate("eroleplay-f4-fechada-outro");
		join(player, campaignId);
		join(outro, campaignId);

		JsonNode closed = body(roll(player, campaignId, "f4-fechada-1", "1d20", "escondido", "PRIVATE"));
		String rollId = closed.get("id").asText();

		// A propria rolagem fechada continua no historico de quem rolou — fechar é para os outros.
		JsonNode ownHistory = body(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/rolls")
				.header("X-Session-Token", player.token()))
			.andExpect(status().isOk())
			.andReturn());
		assertThat(ids(ownHistory)).contains(rollId);

		// O vizinho de mesa não a vê na lista...
		JsonNode otherHistory = body(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/rolls")
				.header("X-Session-Token", outro.token()))
			.andExpect(status().isOk())
			.andReturn());
		assertThat(ids(otherHistory)).doesNotContain(rollId);

		// ...e não a alcança pelo id.
		MvcResult direct = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/rolls/" + rollId)
				.header("X-Session-Token", outro.token()))
			.andReturn();
		assertThat(direct.getResponse().getStatus()).isEqualTo(403);
		assertThat(direct.getResponse().getContentAsString()).contains("FORBIDDEN");

		// O mestre também não: privado é a exceção do dono.
		MvcResult masterRead = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/rolls/" + rollId)
				.header("X-Session-Token", master.token()))
			.andReturn();
		assertThat(masterRead.getResponse().getStatus()).isEqualTo(403);
		assertThat(masterRead.getResponse().getContentAsString()).doesNotContain("escondido");
	}

	@Test
	void oMestrePedeOJogadorResponde() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4-pedido-mestre");
		String campaignId = createCampaign(master, "Pedido de Rolagem", "f4-create-pedido");

		var player = authHelper().authenticate("eroleplay-f4-pedido-jogador");
		join(player, campaignId);

		MvcResult requested = requestRoll(master, campaignId, "f4-pedido-1", player.userId(), "1d20", "GAME_MASTER");
		assertThat(requested.getResponse().getStatus()).isEqualTo(201);
		JsonNode request = body(requested);
		assertThat(request.get("status").asText()).isEqualTo("PENDING");
		assertThat(request.get("playerId").asText()).isEqualTo(player.userId());
		assertThat(request.get("expression").asText()).isEqualTo("1d20");
		String requestId = request.get("id").asText();

		MvcResult answered = answer(player, campaignId, requestId, "f4-pedido-resposta-1", null);
		assertThat(answered.getResponse().getStatus()).isEqualTo(201);
		JsonNode roll = body(answered);
		assertThat(roll.get("requestId").asText()).isEqualTo(requestId);
		assertThat(roll.get("visibility").asText()).isEqualTo("GAME_MASTER");
		assertThat(roll.get("label").asText()).isEqualTo("percepcao");

		JsonNode after = body(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/rolls/requests/" + requestId)
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn());
		assertThat(after.get("status").asText()).isEqualTo("ANSWERED");
		assertThat(after.get("answeredRollId").asText()).isEqualTo(roll.get("id").asText());
	}

	@Test
	void responderDuasVezesERecusado() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4-duas-mestre");
		String campaignId = createCampaign(master, "Resposta Dupla", "f4-create-duas");

		var player = authHelper().authenticate("eroleplay-f4-duas-jogador");
		join(player, campaignId);

		String requestId = body(requestRoll(master, campaignId, "f4-duas-1", player.userId(), "1d20", "CAMPAIGN"))
			.get("id").asText();

		assertThat(answer(player, campaignId, requestId, "f4-duas-resposta-1", null).getResponse().getStatus())
			.isEqualTo(201);

		MvcResult second = answer(player, campaignId, requestId, "f4-duas-resposta-2", null);
		assertThat(second.getResponse().getStatus()).isEqualTo(409);
		assertThat(second.getResponse().getContentAsString()).contains("ROLL_REQUEST_ALREADY_ANSWERED");
	}

	@Test
	void oJogadorNaoTrocaAExpressaoPedida() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4-expressao-mestre");
		String campaignId = createCampaign(master, "Expressao do Mestre", "f4-create-expressao");

		var player = authHelper().authenticate("eroleplay-f4-expressao-jogador");
		join(player, campaignId);
		String requestId = body(requestRoll(master, campaignId, "f4-expressao-1", player.userId(), "1d20", "CAMPAIGN"))
			.get("id").asText();

		// O corpo da resposta não tem campo de expressão — mandar um é ignorado, e o que rola é o pedido.
		MvcResult answered = mockMvc.perform(
				post("/roleplay/campaigns/" + campaignId + "/rolls/requests/" + requestId + "/answer")
					.header("X-Session-Token", player.token())
					.header("Idempotency-Key", "f4-expressao-resposta-1")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("expression", "1d1", "label", "percepcao"))))
			.andReturn();
		assertThat(answered.getResponse().getStatus()).isEqualTo(201);
		assertThat(body(answered).get("expression").asText()).isEqualTo("1d20");
	}

	@Test
	void oRotuloContraditorioERecusado() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4-rotulo-mestre");
		String campaignId = createCampaign(master, "Rotulo", "f4-create-rotulo");

		var player = authHelper().authenticate("eroleplay-f4-rotulo-jogador");
		join(player, campaignId);
		String requestId = body(requestRoll(master, campaignId, "f4-rotulo-1", player.userId(), "1d20", "CAMPAIGN"))
			.get("id").asText();

		MvcResult contradicted = answer(player, campaignId, requestId, "f4-rotulo-resposta-1", "dano");
		assertThat(contradicted.getResponse().getStatus()).isEqualTo(400);
		assertThat(contradicted.getResponse().getContentAsString()).contains("ROLL_LABEL_MISMATCH");
	}

	@Test
	void sortearPedidoFechadoNaoAbreMaisQueOPedido() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4-teto-mestre");
		String campaignId = createCampaign(master, "Teto de Visibilidade", "f4-create-teto");

		var player = authHelper().authenticate("eroleplay-f4-teto-jogador");
		join(player, campaignId);
		// O pedido nasce fechado ao mestre; o jogador pede CAMPAIGN na resposta.
		String requestId = body(requestRoll(master, campaignId, "f4-teto-1", player.userId(), "1d20", "GAME_MASTER"))
			.get("id").asText();

		MvcResult answered = mockMvc.perform(
				post("/roleplay/campaigns/" + campaignId + "/rolls/requests/" + requestId + "/answer")
					.header("X-Session-Token", player.token())
					.header("Idempotency-Key", "f4-teto-resposta-1")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("visibility", "CAMPAIGN"))))
			.andReturn();
		assertThat(answered.getResponse().getStatus()).isEqualTo(201);
		assertThat(body(answered).get("visibility").asText()).isEqualTo("GAME_MASTER");
	}

	@Test
	void oMesmoIdempotencyKeyNaoRolaDuasVezes() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4-idem-mestre");
		String campaignId = createCampaign(master, "Idempotencia", "f4-create-idem");

		JsonNode first = body(roll(master, campaignId, "f4-idem-1", "1d20", "percepcao", "CAMPAIGN"));
		JsonNode replay = body(roll(master, campaignId, "f4-idem-1", "1d20", "percepcao", "CAMPAIGN"));

		assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
		assertThat(replay.get("total").asLong()).isEqualTo(first.get("total").asLong());

		JsonNode history = body(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/rolls")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn());
		assertThat(ids(history)).containsExactly(first.get("id").asText());
	}

	@Test
	void oMestreVeOsPedidosQueFezEOJogadorOsQueRecebeu() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4-lista-mestre");
		String campaignId = createCampaign(master, "Lista de Pedidos", "f4-create-lista");

		var primeiro = authHelper().authenticate("eroleplay-f4-lista-um");
		var segundo = authHelper().authenticate("eroleplay-f4-lista-dois");
		join(primeiro, campaignId);
		join(segundo, campaignId);

		requestRoll(master, campaignId, "f4-lista-1", primeiro.userId(), "1d20", "CAMPAIGN");
		requestRoll(master, campaignId, "f4-lista-2", segundo.userId(), "2d6", "CAMPAIGN");

		JsonNode masterList = body(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/rolls/requests")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn());
		assertThat(masterList).hasSize(2);

		JsonNode firstList = body(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/rolls/requests")
				.header("X-Session-Token", primeiro.token()))
			.andExpect(status().isOk())
			.andReturn());
		assertThat(firstList).hasSize(1);
		assertThat(firstList.get(0).get("playerId").asText()).isEqualTo(primeiro.userId());
	}

	@Test
	void expressaoInvalidaERecusadaSemGravar() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4-invalida-mestre");
		String campaignId = createCampaign(master, "Expressao Invalida", "f4-create-invalida");

		MvcResult invalid = roll(master, campaignId, "f4-invalida-1", "2d6*3", "ataque", "CAMPAIGN");
		assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
		assertThat(invalid.getResponse().getContentAsString()).contains("DICE_EXPRESSION_INVALID");

		JsonNode history = body(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/rolls")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn());
		assertThat(history).isEmpty();
	}

	private static java.util.List<String> ids(JsonNode array) {
		java.util.List<String> ids = new java.util.ArrayList<>();
		for (JsonNode node : array) {
			ids.add(node.get("id").asText());
		}
		return ids;
	}
}
