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
 * O World Builder de ponta a ponta: cria, lê, edita, apaga, busca — e o que foi gravado
 * sobrevive à leitura seguinte. O mesmo Idempotency-Key não grava duas.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorldFlowTests {

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
					"description", "campanha de teste do world builder",
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

	/** Cria ficha da campanha (modelo padrão + PUT /sheets/me), para vincular entradas de mundo a ela. */
	private String createSheet(TestAuthHelper.AuthSession master, TestAuthHelper.AuthSession player,
			String campaignId, String name) throws Exception {
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/sheets/template")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f7-template-" + name)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fields",
					java.util.List.of(Map.of("key", "forca", "maxVisibility", "CAMPAIGN"),
						Map.of("key", "historia", "maxVisibility", "CAMPAIGN"))))))
			.andReturn();
		MvcResult result = mockMvc.perform(put("/roleplay/campaigns/" + campaignId + "/sheets/me")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "f7-sheet-" + name)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "entries",
					fichaCompleta()))))
			.andReturn();
		JsonNode corpo = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(corpo.get("id")).as("fill deve devolver a ficha: %s", corpo.toString()).isNotNull();
		return corpo.get("id").asText();
	}

	/** A ficha completa do D20 original (campos obrigatórios do modelo), história aberta à campanha. */
	private java.util.List<Map<String, String>> fichaCompleta() {
		return new java.util.ArrayList<>(java.util.List.of(
			Map.of("key", "forca", "value", "12", "visibility", "CAMPAIGN"),
			Map.of("key", "destreza", "value", "14", "visibility", "GAME_MASTER"),
			Map.of("key", "constituicao", "value", "13", "visibility", "GAME_MASTER"),
			Map.of("key", "inteligencia", "value", "11", "visibility", "GAME_MASTER"),
			Map.of("key", "sabedoria", "value", "10", "visibility", "GAME_MASTER"),
			Map.of("key", "carisma", "value", "9", "visibility", "GAME_MASTER"),
			Map.of("key", "nivel", "value", "1", "visibility", "GAME_MASTER"),
			Map.of("key", "pontos_de_vida", "value", "8", "visibility", "GAME_MASTER"),
			Map.of("key", "defesa", "value", "14", "visibility", "GAME_MASTER"),
			Map.of("key", "origem", "value", "Artista", "visibility", "GAME_MASTER"),
			Map.of("key", "historia", "value", "criada para vincular ao mundo", "visibility", "CAMPAIGN")));
	}

	@Test
	void entradaNasceNoAtlasPersisteEProjetaNomeDaFichaSemVazarOId() throws Exception {
		var master = authHelper().authenticate("f7-fluxo-mestre");
		var player = authHelper().authenticate("f7-fluxo-jogador");
		String campaignId = createCampaign(master, "Mundo do Fluxo", "f7-create-fluxo");
		join(player, campaignId, "f7-join-fluxo");
		String sheetId = createSheet(master, player, campaignId, "Aria do Fluxo");

		// O JOGADOR registra — o mundo é do grupo, não só do mestre.
		JsonNode npc = postJson(player, "/roleplay/campaigns/" + campaignId + "/world", "f7-cria-npc",
			Map.of("kind", "CHARACTER", "title", "Aria, a guardiã", "body", "Vive na torre e guarda a ponte.",
				"tags", java.util.List.of("npc", "torre"), "sheetId", sheetId));
		assertThat(npc.has("code")).isFalse();
		String entryId = npc.get("id").asText();
		assertThat(npc.get("kind").asText()).isEqualTo("CHARACTER");
		assertThat(npc.get("linkedToSheet").asBoolean()).isTrue();
		assertThat(npc.get("linkedSheetName").asText()).isEqualTo("Aria do Fluxo");
		// O id da ficha não aparece em lugar algum da resposta.
		assertThat(npc.toString()).doesNotContain(sheetId);

		// Persistiu: a leitura seguinte traz a mesma entrada, também sem o id da ficha.
		MvcResult leitura = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/world/" + entryId)
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode relida = objectMapper.readTree(leitura.getResponse().getContentAsString());
		assertThat(relida.get("title").asText()).isEqualTo("Aria, a guardiã");
		assertThat(relida.get("linkedSheetName").asText()).isEqualTo("Aria do Fluxo");
		assertThat(relida.toString()).doesNotContain(sheetId);
	}

	@Test
	void atlasFiltraPorTipoEBuscaTexto() throws Exception {
		var master = authHelper().authenticate("f7-atlas-mestre");
		String campaignId = createCampaign(master, "Mundo do Atlas", "f7-create-atlas");
		postJson(master, "/roleplay/campaigns/" + campaignId + "/world", "f7-atlas-1",
			Map.of("kind", "LOCALE", "title", "Porto de Sal", "body", "Cidade litorânea.", "tags",
				java.util.List.of("cidade")));
		postJson(master, "/roleplay/campaigns/" + campaignId + "/world", "f7-atlas-2",
			Map.of("kind", "ITEM", "title", "Mapa do naufrágio", "tags", java.util.List.of("mapa")));
		postJson(master, "/roleplay/campaigns/" + campaignId + "/world", "f7-atlas-3",
			Map.of("kind", "LORE", "title", "O grande maremoto", "body", "Dividiu o reino em dois."));

		MvcResult porTipo = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/world")
				.header("X-Session-Token", master.token())
				.param("kind", "LOCALE"))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode locais = objectMapper.readTree(porTipo.getResponse().getContentAsString());
		assertThat(locais.size()).isEqualTo(1);
		assertThat(locais.get(0).get("title").asText()).isEqualTo("Porto de Sal");

		MvcResult porTexto = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/world")
				.header("X-Session-Token", master.token())
				.param("q", "maremoto"))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode achados = objectMapper.readTree(porTexto.getResponse().getContentAsString());
		assertThat(achados.size()).isEqualTo(1);
		assertThat(achados.get(0).get("title").asText()).isEqualTo("O grande maremoto");
	}

	@Test
	void autorEditaEApagaOQueEscreveuEMesmoIdempotencyKeyNaoGravaDuas() throws Exception {
		var master = authHelper().authenticate("f7-eda-mestre");
		var player = authHelper().authenticate("f7-eda-jogador");
		String campaignId = createCampaign(master, "Mundo da Edição", "f7-create-eda");
		join(player, campaignId, "f7-join-eda");

		JsonNode criada = postJson(player, "/roleplay/campaigns/" + campaignId + "/world", "f7-eda-cria",
			Map.of("kind", "PLOT", "title", "Rumor do poço", "body", "Dizem que tem um fundo."));
		String entryId = criada.get("id").asText();

		// O mesmo Idempotency-Key duas vezes: uma entrada só no atlas.
		postJson(player, "/roleplay/campaigns/" + campaignId + "/world", "f7-eda-cria",
			Map.of("kind", "PLOT", "title", "Rumor do poço", "body", "Dizem que tem um fundo."));
		MvcResult atlas = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/world")
				.header("X-Session-Token", player.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(objectMapper.readTree(atlas.getResponse().getContentAsString()).size()).isEqualTo(1);

		// O autor edita o que é dele.
		MvcResult edicao = mockMvc.perform(put("/roleplay/campaigns/" + campaignId + "/world/" + entryId)
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "f7-eda-put")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("kind", "PLOT", "title", "Rumor do poço fundo",
					"body", "Tem escada, sim.", "tags", java.util.List.of("rumor")))))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode editada = objectMapper.readTree(edicao.getResponse().getContentAsString());
		assertThat(editada.get("title").asText()).isEqualTo("Rumor do poço fundo");
		assertThat(editada.get("body").asText()).isEqualTo("Tem escada, sim.");

		// O autor apaga o que é dele; o atlas fica vazio para esta campanha (dados só nossos).
		mockMvc.perform(delete("/roleplay/campaigns/" + campaignId + "/world/" + entryId)
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "f7-eda-delete"))
			.andExpect(status().isOk());
		MvcResult vazio = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/world")
				.header("X-Session-Token", player.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(objectMapper.readTree(vazio.getResponse().getContentAsString()).size()).isZero();
	}

	@Test
	void entradaInvalidaVolta400SemGravar() throws Exception {
		var master = authHelper().authenticate("f7-inv-mestre");
		String campaignId = createCampaign(master, "Mundo Inválido", "f7-create-inv");

		JsonNode semTipo = postJson(master, "/roleplay/campaigns/" + campaignId + "/world", "f7-inv-1",
			Map.of("title", "Sem tipo"));
		assertThat(semTipo.get("code").asText()).isEqualTo("WORLD_KIND_REQUIRED");

		JsonNode tipoEstranho = postJson(master, "/roleplay/campaigns/" + campaignId + "/world", "f7-inv-2",
			Map.of("kind", "DRAGAO", "title", "Tipo inventado"));
		assertThat(tipoEstranho.get("code").asText()).isEqualTo("WORLD_KIND_INVALID");

		JsonNode semTitulo = postJson(master, "/roleplay/campaigns/" + campaignId + "/world", "f7-inv-3",
			Map.of("kind", "LORE", "title", "   "));
		assertThat(semTitulo.get("code").asText()).isEqualTo("WORLD_TITLE_REQUIRED");

		JsonNode vinculoFalso = postJson(master, "/roleplay/campaigns/" + campaignId + "/world", "f7-inv-4",
			Map.of("kind", "CHARACTER", "title", "Vínculo falso", "sheetId", "sheet_nao_existe"));
		assertThat(vinculoFalso.get("code").asText()).isEqualTo("SHEET_NOT_FOUND");

		MvcResult atlas = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/world")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(objectMapper.readTree(atlas.getResponse().getContentAsString()).size()).isZero();
	}
}
