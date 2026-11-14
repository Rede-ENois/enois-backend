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
 * O isolamento e a integridade do registro de rolagem no backend.
 *
 * <p>O que este teste tranca:</p>
 *
 * <ul>
 * <li>nenhuma rota de rolagem responde sem sessão, nem leitura;</li>
 * <li>quem não participa da campanha não lê histórico, pedido nem rolagem, mesmo com o id;</li>
 * <li>jogador não pede rolagem a outro — pedir é do mestre;</li>
 * <li>o jogador não responde pedido feito a outro;</li>
 * <li>o registro é só-leitura na API: não existe rota que o reescreva ou o remova;</li>
 * <li>o resultado nunca vem do corpo: o campo que representaria o resultado é ignorado.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DiceSecurityTests {

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
				.header("Idempotency-Key", "f4sec-join-" + campaignId + "-" + user.userId()))
			.andExpect(status().isOk());
	}

	private String rollOnce(TestAuthHelper.AuthSession user, String campaignId, String idemKey) throws Exception {
		MvcResult result = mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/rolls")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("expression", "1d20", "visibility", "CAMPAIGN"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void nenhumaRotaDeRolagemRespondeSemSessao() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4sec-sessao-mestre");
		String campaignId = createCampaign(master, "Dados Sem Sessao", "f4sec-create-sessao");

		String base = "/roleplay/campaigns/" + campaignId + "/rolls";

		mockMvc.perform(get(base)).andExpect(status().isUnauthorized());
		mockMvc.perform(get(base + "/roll_qualquer")).andExpect(status().isUnauthorized());
		mockMvc.perform(get(base + "/requests")).andExpect(status().isUnauthorized());
		mockMvc.perform(get(base + "/requests/req_qualquer")).andExpect(status().isUnauthorized());

		mockMvc.perform(post(base)
				.header("Idempotency-Key", "f4sec-roll-anon")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("expression", "1d20"))))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post(base + "/requests")
				.header("Idempotency-Key", "f4sec-request-anon")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("playerId", "x", "expression", "1d20"))))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post(base + "/requests/req_qualquer/answer")
				.header("Idempotency-Key", "f4sec-answer-anon")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void quemNaoParticipaNaoLeNemRola() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4sec-fora-mestre");
		String campaignId = createCampaign(master, "Dados Fechados", "f4sec-create-fora");
		// O mestre já participa: quem cria a campanha nasce dentro dela.
		String rollId = rollOnce(master, campaignId, "f4sec-fora-roll-1");

		var outsider = authHelper().authenticate("eroleplay-f4sec-fora");
		String base = "/roleplay/campaigns/" + campaignId + "/rolls";

		assertThat(mockMvc.perform(get(base).header("X-Session-Token", outsider.token()))
			.andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(mockMvc.perform(get(base + "/" + rollId).header("X-Session-Token", outsider.token()))
			.andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(mockMvc.perform(get(base + "/requests").header("X-Session-Token", outsider.token()))
			.andReturn().getResponse().getStatus()).isEqualTo(403);

		MvcResult write = mockMvc.perform(post(base)
				.header("X-Session-Token", outsider.token())
				.header("Idempotency-Key", "f4sec-fora-roll")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("expression", "1d20"))))
			.andReturn();
		assertThat(write.getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void jogadorNaoPedeRolagemANinguem() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4sec-pede-mestre");
		String campaignId = createCampaign(master, "Quem Pede", "f4sec-create-pede");

		var player = authHelper().authenticate("eroleplay-f4sec-pede-jogador");
		var outro = authHelper().authenticate("eroleplay-f4sec-pede-outro");
		join(player, campaignId);
		join(outro, campaignId);

		MvcResult asked = mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/rolls/requests")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "f4sec-pede-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("playerId", outro.userId(), "expression", "1d20"))))
			.andReturn();
		assertThat(asked.getResponse().getStatus()).isEqualTo(403);

		// O mestre rola para si mesmo direto: pedido a si não existe.
		MvcResult self = mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/rolls/requests")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f4sec-pede-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("playerId", master.userId(), "expression", "1d20"))))
			.andReturn();
		assertThat(self.getResponse().getStatus()).isEqualTo(400);
		assertThat(self.getResponse().getContentAsString()).contains("ROLL_REQUEST_SELF");
	}

	@Test
	void oPedidoNaoERespondidoPorOutro() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4sec-resp-mestre");
		String campaignId = createCampaign(master, "Quem Responde", "f4sec-create-resp");

		var player = authHelper().authenticate("eroleplay-f4sec-resp-jogador");
		var intruso = authHelper().authenticate("eroleplay-f4sec-resp-intruso");
		join(player, campaignId);
		join(intruso, campaignId);

		MvcResult requested = mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/rolls/requests")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f4sec-resp-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("playerId", player.userId(), "expression", "1d20"))))
			.andExpect(status().isCreated())
			.andReturn();
		String requestId = objectMapper.readTree(requested.getResponse().getContentAsString()).get("id").asText();

		// O intruso é participante e tem id na mão: ainda assim não responde pedido alheio.
		MvcResult stolen = mockMvc.perform(
				post("/roleplay/campaigns/" + campaignId + "/rolls/requests/" + requestId + "/answer")
					.header("X-Session-Token", intruso.token())
					.header("Idempotency-Key", "f4sec-resp-roubo")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
			.andReturn();
		assertThat(stolen.getResponse().getStatus()).isEqualTo(403);
		assertThat(stolen.getResponse().getContentAsString()).contains("FORBIDDEN");

		// O pedido continua pendente para quem deve respondê-lo.
		MvcResult read = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/rolls/requests/" + requestId)
				.header("X-Session-Token", player.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(objectMapper.readTree(read.getResponse().getContentAsString()).get("status").asText())
			.isEqualTo("PENDING");
	}

	@Test
	void oRegistroESoLeituraNaApi() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4sec-imutavel-mestre");
		String campaignId = createCampaign(master, "Registro Imutavel", "f4sec-create-imutavel");
		String rollId = rollOnce(master, campaignId, "f4sec-imutavel-1");
		String base = "/roleplay/campaigns/" + campaignId + "/rolls";

		// As rotas de alteração e remoção não existem: o verbo encontra a rota de leitura errada ou
		// nenhuma — e nada reescreve o registro.
		MvcResult put = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.put(base + "/" + rollId)
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f4sec-imutavel-put")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("total", 999))))
			.andReturn();
		// PUT /rolls/{id} não casa com rota nenhuma: sem handler de PUT, o app responde 405 —
		// e o handler global devolve corpo próprio, não uma ficha reescrita.
		assertThat(put.getResponse().getStatus()).isEqualTo(405);

		MvcResult delete = mockMvc.perform(
				org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base + "/" + rollId)
					.header("X-Session-Token", master.token())
					.header("Idempotency-Key", "f4sec-imutavel-delete"))
			.andReturn();
		assertThat(delete.getResponse().getStatus()).isEqualTo(405);

		// Depois das tentativas, o registro continua o que foi sorteado.
		MvcResult read = mockMvc.perform(get(base + "/" + rollId).header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode roll = objectMapper.readTree(read.getResponse().getContentAsString());
		assertThat(roll.get("total").asLong()).isBetween(1L, 20L);
		assertThat(roll.get("expression").asText()).isEqualTo("1d20");
	}

	@Test
	void oResultadoNuncaVemDoCorpo() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4sec-corpo-mestre");
		String campaignId = createCampaign(master, "Resultado do Corpo", "f4sec-create-corpo");

		// O corpo manda "total 20 e dado 20": o servidor sorteia do mesmo jeito.
		for (int attempt = 0; attempt < 10; attempt++) {
			MvcResult result = mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/rolls")
					.header("X-Session-Token", master.token())
					.header("Idempotency-Key", "f4sec-corpo-" + attempt)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("expression", "1d20", "total", 20,
						"dice", java.util.List.of(20)))))
				.andExpect(status().isCreated())
				.andReturn();
			JsonNode roll = objectMapper.readTree(result.getResponse().getContentAsString());
			assertThat(roll.get("total").asLong()).isBetween(1L, 20L);
		}
	}

	@Test
	void aExpressaoDoHistoricoEMesmaDaLeituraDireta() throws Exception {
		var master = authHelper().authenticate("eroleplay-f4sec-coerencia-mestre");
		String campaignId = createCampaign(master, "Coerencia do Registro", "f4sec-create-coerencia");
		String rollId = rollOnce(master, campaignId, "f4sec-coerencia-1");

		String history = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/rolls")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String direct = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/rolls/" + rollId)
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		// A mesma rolagem lida pelos dois caminhos é o mesmo registro — sem recálculo no meio.
		assertThat(historyFragment(history, rollId)).isEqualTo(direct);
		assertThat(objectMapper.readTree(direct).get("id").asText()).isEqualTo(rollId);
	}

	/** O objeto do histórico cujo id é o informado. */
	private String historyFragment(String history, String rollId) throws Exception {
		for (var node : objectMapper.readTree(history)) {
			if (rollId.equals(node.get("id").asText())) {
				return node.toString();
			}
		}
		return "";
	}
}
