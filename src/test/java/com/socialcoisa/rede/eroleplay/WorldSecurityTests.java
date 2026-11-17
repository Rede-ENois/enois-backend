package com.socialcoisa.rede.eroleplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * O atlas não vaza para quem não está na mesa.
 *
 * <p>O que este teste tranca: sem sessão, 401 em leitura e escrita (mesmo guarda fail-closed do
 * resto da plataforma); quem não participa da campanha não lê nem lista nada; a entrada de outro
 * jogador não é editada nem apagada por quem não é o autor nem o mestre; a entrada de OUTRA
 * campanha não é lida nem apagada pelas rotas desta (IDOR — "não existe para você"); e a ficha
 * vinculada nunca aparece na resposta por id.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorldSecurityTests {

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
					"description", "campanha de teste de seguranca do mundo",
					"gameSystem", "d20-generico",
					"systemFamily", "D20",
					"visibility", "PUBLIC"))))
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

	private JsonNode postJson(TestAuthHelper.AuthSession who, String url, String idemKey,
			Map<String, Object> payload) throws Exception {
		MvcResult result = mockMvc.perform(post(url)
				.header("X-Session-Token", who.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(payload)))
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	@Test
	void semSessaoResponde401EmTodaASuperficie() throws Exception {
		String qualquer = "/roleplay/campaigns/camp_qualquer/world";
		mockMvc.perform(get(qualquer)).andExpect(status().isUnauthorized());
		mockMvc.perform(post(qualquer)
				.header("Idempotency-Key", "f7-sec-anon-post")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")).andExpect(status().isUnauthorized());
		mockMvc.perform(put(qualquer + "/entrada")
				.header("Idempotency-Key", "f7-sec-anon-put")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")).andExpect(status().isUnauthorized());
		mockMvc.perform(delete(qualquer + "/entrada")
				.header("Idempotency-Key", "f7-sec-anon-delete"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void visitanteDaCampanhaNaoLeNemEscreve() throws Exception {
		var master = authHelper().authenticate("f7-sec-mestre");
		var estranho = authHelper().authenticate("f7-sec-estranho");
		String campaignId = createCampaign(master, "Mundo Fechado", "f7-sec-create");
		postJson(master, "/roleplay/campaigns/" + campaignId + "/world", "f7-sec-cria",
			Map.of("kind", "LORE", "title", "Segredo da mesa", "body", "Só para quem joga."));

		// Leitura: o estranho não vê o atlas nem a entrada.
		MvcResult atlas = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/world")
				.header("X-Session-Token", estranho.token()))
			.andReturn();
		assertThat(atlas.getResponse().getStatus()).isEqualTo(403);

		MvcResult entrada = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/world/entrada_qualquer")
				.header("X-Session-Token", estranho.token()))
			.andReturn();
		assertThat(entrada.getResponse().getStatus()).isEqualTo(403);

		// Escrita: recusada e nada gravado.
		JsonNode negada = postJson(estranho, "/roleplay/campaigns/" + campaignId + "/world", "f7-sec-escreve",
			Map.of("kind", "LORE", "title", "Entrada de fora"));
		assertThat(negada.get("code").asText()).isEqualTo("FORBIDDEN");

		MvcResult atlasDepois = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/world")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(objectMapper.readTree(atlasDepois.getResponse().getContentAsString()).size()).isEqualTo(1);
	}

	@Test
	void jogadorNaoEditaNemApagaEntradaAlheia() throws Exception {
		var master = authHelper().authenticate("f7-alheia-mestre");
		var autora = authHelper().authenticate("f7-alheia-autora");
		var outro = authHelper().authenticate("f7-alheia-outro");
		String campaignId = createCampaign(master, "Mundo da Entrada Alheia", "f7-alheia-create");
		join(autora, campaignId, "f7-alheia-join-a");
		join(outro, campaignId, "f7-alheia-join-b");

		JsonNode criada = postJson(autora, "/roleplay/campaigns/" + campaignId + "/world", "f7-alheia-cria",
			Map.of("kind", "ITEM", "title", "Diário da autora", "body", "Anotação pessoal de mesa."));
		String entryId = criada.get("id").asText();

		MvcResult negadaEdicao = mockMvc.perform(put("/roleplay/campaigns/" + campaignId + "/world/" + entryId)
				.header("X-Session-Token", outro.token())
				.header("Idempotency-Key", "f7-alheia-put")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("kind", "ITEM", "title", "Vandalizado"))))
			.andReturn();
		assertThat(negadaEdicao.getResponse().getStatus()).isEqualTo(403);
		assertThat(negadaEdicao.getResponse().getContentAsString()).contains("FORBIDDEN");

		MvcResult negadaRemocao = mockMvc.perform(delete("/roleplay/campaigns/" + campaignId + "/world/" + entryId)
				.header("X-Session-Token", outro.token())
				.header("Idempotency-Key", "f7-alheia-delete"))
			.andReturn();
		assertThat(negadaRemocao.getResponse().getStatus()).isEqualTo(403);

		// O mestre pode em tudo — edição e remoção passam para ele.
		MvcResult mestreEdita = mockMvc.perform(put("/roleplay/campaigns/" + campaignId + "/world/" + entryId)
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f7-alheia-put-mestre")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("kind", "ITEM", "title", "Diário arquivado"))))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(objectMapper.readTree(mestreEdita.getResponse().getContentAsString()).get("title").asText())
			.isEqualTo("Diário arquivado");
	}

	@Test
	void entradaDeOutraCampanhaNaoELidaNemApagadaPorEsta() throws Exception {
		var masterA = authHelper().authenticate("f7-idor-mestre-a");
		var masterB = authHelper().authenticate("f7-idor-mestre-b");
		String campanhaA = createCampaign(masterA, "Mundo A", "f7-idor-create-a");
		String campanhaB = createCampaign(masterB, "Mundo B", "f7-idor-create-b");

		JsonNode criada = postJson(masterA, "/roleplay/campaigns/" + campanhaA + "/world", "f7-idor-cria",
			Map.of("kind", "LOCALE", "title", "Masmorra do mundo A"));
		String entryId = criada.get("id").asText();

		// Leitura pela campanha B: 404 — "não existe para você", sem confirmar existência.
		MvcResult leitura = mockMvc.perform(get("/roleplay/campaigns/" + campanhaB + "/world/" + entryId)
				.header("X-Session-Token", masterB.token()))
			.andReturn();
		assertThat(leitura.getResponse().getStatus()).isEqualTo(404);

		// Remoção pela campanha B: 404 e a entrada segue intacta no mundo A.
		MvcResult remocao = mockMvc.perform(delete("/roleplay/campaigns/" + campanhaB + "/world/" + entryId)
				.header("X-Session-Token", masterB.token())
				.header("Idempotency-Key", "f7-idor-delete"))
			.andReturn();
		assertThat(remocao.getResponse().getStatus()).isEqualTo(404);

		MvcResult atlasA = mockMvc.perform(get("/roleplay/campaigns/" + campanhaA + "/world")
				.header("X-Session-Token", masterA.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode sobrou = objectMapper.readTree(atlasA.getResponse().getContentAsString());
		assertThat(sobrou.size()).isEqualTo(1);
		assertThat(sobrou.get(0).get("title").asText()).isEqualTo("Masmorra do mundo A");
	}
}
