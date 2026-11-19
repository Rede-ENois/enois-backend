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
 * A biblioteca de ponta a ponta: o dono publica, a cópia sai pública com o recorte que ele abriu,
 * republicar atualiza sem duplicar, despublicar apaga a cópia sem tocar na ficha — e o id interno
 * da ficha nunca aparece na resposta pública.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LibraryFlowTests {

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
					"description", "campanha de teste da biblioteca",
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

	/** Modelo com um campo aberto à campanha (vira público) e um só do mestre (não vira). */
	private void defineTemplate(TestAuthHelper.AuthSession master, String campaignId, String idemKey)
			throws Exception {
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/sheets/template")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fields",
					java.util.List.of(Map.of("key", "forca", "maxVisibility", "CAMPAIGN"),
						Map.of("key", "historia", "maxVisibility", "GAME_MASTER"))))))
			.andReturn();
	}

	private String fillSheet(TestAuthHelper.AuthSession player, String campaignId, String name, String idemKey)
			throws Exception {
		MvcResult result = mockMvc.perform(put("/roleplay/campaigns/" + campaignId + "/sheets/me")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "entries", ficha()))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private java.util.List<Map<String, String>> ficha() {
		return java.util.List.of(
			Map.of("key", "forca", "value", "15", "visibility", "CAMPAIGN"),
			Map.of("key", "destreza", "value", "14", "visibility", "GAME_MASTER"),
			Map.of("key", "constituicao", "value", "13", "visibility", "GAME_MASTER"),
			Map.of("key", "inteligencia", "value", "11", "visibility", "GAME_MASTER"),
			Map.of("key", "sabedoria", "value", "10", "visibility", "GAME_MASTER"),
			Map.of("key", "carisma", "value", "9", "visibility", "GAME_MASTER"),
			Map.of("key", "nivel", "value", "1", "visibility", "GAME_MASTER"),
			Map.of("key", "pontos_de_vida", "value", "8", "visibility", "GAME_MASTER"),
			Map.of("key", "defesa", "value", "14", "visibility", "GAME_MASTER"),
			Map.of("key", "origem", "value", "Artista", "visibility", "GAME_MASTER"),
			Map.of("key", "historia", "value", "segredo da mesa", "visibility", "GAME_MASTER"));
	}

	/** A ficha com o valor aberto MUDADO na republicação (preenchimento substitutivo). */
	private java.util.List<Map<String, String>> fichaAtualizada() {
		return java.util.List.of(
			Map.of("key", "forca", "value", "17", "visibility", "CAMPAIGN"),
			Map.of("key", "destreza", "value", "14", "visibility", "GAME_MASTER"),
			Map.of("key", "constituicao", "value", "13", "visibility", "GAME_MASTER"),
			Map.of("key", "inteligencia", "value", "11", "visibility", "GAME_MASTER"),
			Map.of("key", "sabedoria", "value", "10", "visibility", "GAME_MASTER"),
			Map.of("key", "carisma", "value", "9", "visibility", "GAME_MASTER"),
			Map.of("key", "nivel", "value", "1", "visibility", "GAME_MASTER"),
			Map.of("key", "pontos_de_vida", "value", "8", "visibility", "GAME_MASTER"),
			Map.of("key", "defesa", "value", "14", "visibility", "GAME_MASTER"),
			Map.of("key", "origem", "value", "Artista", "visibility", "GAME_MASTER"),
			Map.of("key", "historia", "value", "segredo da mesa", "visibility", "GAME_MASTER"));
	}

	@Test
	void donoPublicaACopiaSaiComORecorteAbertoESemVazarOIdDaFicha() throws Exception {
		TestAuthHelper helper = authHelper();
		var master = helper.authenticate("lib_master_a");
		var player = helper.authenticate("lib_player_a");
		String campaignId = createCampaign(master, "Biblioteca A", "lib-a-camp");
		join(player, campaignId, "lib-a-join");
		defineTemplate(master, campaignId, "lib-a-template");
		String sheetId = fillSheet(player, campaignId, "Aria Publicada", "lib-a-sheet");

		MvcResult pub = mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/sheets/" + sheetId + "/publish")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "lib-a-pub"))
			.andExpect(status().isCreated())
			.andReturn();
		JsonNode publicado = objectMapper.readTree(pub.getResponse().getContentAsString());

		// O recorte público: o que o dono abriu à CAMPAIGN sai; o que era só do mestre, não.
		assertThat(publicado.get("characterName").asText()).isEqualTo("Aria Publicada");
		assertThat(publicado.get("gameSystem").asText()).isEqualTo("d20-generico");
		assertThat(publicado.get("fields").get("forca").asText()).isEqualTo("15");
		assertThat(publicado.has("historia")).as("campo fechado não vira público: %s", publicado).isFalse();
		assertThat(publicado.get("id").asText()).isNotBlank();

		// O id da ficha (e o da campanha) não atravessam a projeção pública.
		String corpo = publicado.toString();
		assertThat(corpo.contains(sheetId)).as("id da ficha vazou: %s", corpo).isFalse();
		assertThat(corpo.contains(campaignId)).as("id da campanha vazou: %s", corpo).isFalse();
		assertThat(corpo.contains(player.userId())).as("id do dono vazou: %s", corpo).isFalse();

		// A leitura pública pelo id da PUBLICAÇÃO devolve o mesmo conteúdo.
		String publicationId = publicado.get("id").asText();
		MvcResult leitura = mockMvc.perform(get("/roleplay/library/" + publicationId))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(objectMapper.readTree(leitura.getResponse().getContentAsString()).get("characterName").asText())
			.isEqualTo("Aria Publicada");
	}

	@Test
	void republicarAtualizaSemDuplicarEDespublicarApagaACopiaSemTocarNaFicha() throws Exception {
		TestAuthHelper helper = authHelper();
		var master = helper.authenticate("lib_master_b");
		var player = helper.authenticate("lib_player_b");
		String campaignId = createCampaign(master, "Biblioteca B", "lib-b-camp");
		join(player, campaignId, "lib-b-join");
		defineTemplate(master, campaignId, "lib-b-template");
		String sheetId = fillSheet(player, campaignId, "Bento Publicado", "lib-b-sheet");

		String primeira = objectMapper
			.readTree(mockMvc
				.perform(post("/roleplay/campaigns/" + campaignId + "/sheets/" + sheetId + "/publish")
					.header("X-Session-Token", player.token()).header("Idempotency-Key", "lib-b-pub-1"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
			.get("id").asText();

		// O dono muda o valor aberto na ficha (o preenchimento é substitutivo) e republica.
		mockMvc.perform(put("/roleplay/campaigns/" + campaignId + "/sheets/me")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "lib-b-sheet-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Bento Publicado", "entries",
					fichaAtualizada()))))
			.andExpect(status().isOk());
		MvcResult segunda = mockMvc
			.perform(post("/roleplay/campaigns/" + campaignId + "/sheets/" + sheetId + "/publish")
				.header("X-Session-Token", player.token()).header("Idempotency-Key", "lib-b-pub-2"))
			.andExpect(status().isCreated()).andReturn();
		JsonNode republicado = objectMapper.readTree(segunda.getResponse().getContentAsString());
		assertThat(republicado.get("id").asText()).isEqualTo(primeira);
		assertThat(republicado.get("fields").get("forca").asText())
			.as("copia atualizada, sem duplicar publicação: %s", republicado).isEqualTo("17");

		// Despublicar: a cópia sai do ar e a leitura pública passa a 404.
		mockMvc.perform(delete("/roleplay/campaigns/" + campaignId + "/sheets/" + sheetId + "/publish")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "lib-b-unpub"))
			.andExpect(status().isOk());
		mockMvc.perform(get("/roleplay/library/" + primeira)).andExpect(status().isNotFound());

		// A ficha DE MESA segue existindo: o dono continua lendo a própria ficha.
		mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/sheets/me")
				.header("X-Session-Token", player.token()))
			.andExpect(status().isOk());
	}

	@Test
	void bibliotecaListaEBuscaPorPersonagemESemPublicadoNaoAparece() throws Exception {
		TestAuthHelper helper = authHelper();
		var master = helper.authenticate("lib_master_c");
		var player = helper.authenticate("lib_player_c");
		String campaignId = createCampaign(master, "Biblioteca C", "lib-c-camp");
		join(player, campaignId, "lib-c-join");
		defineTemplate(master, campaignId, "lib-c-template");
		String publicado = fillSheet(player, campaignId, "Zuleica Procuravel", "lib-c-sheet");
		String oculto = fillSheet(master, campaignId, "Quim Nao Publicado", "lib-c-sheet-2");

		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/sheets/" + publicado + "/publish")
				.header("X-Session-Token", player.token()).header("Idempotency-Key", "lib-c-pub"))
			.andExpect(status().isCreated());

		MvcResult busca = mockMvc.perform(get("/roleplay/library").param("query", "Procuravel"))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode itens = objectMapper.readTree(busca.getResponse().getContentAsString()).get("items");
		assertThat(itens.size()).isGreaterThanOrEqualTo(1);
		boolean achou = false;
		for (JsonNode item : itens) {
			assertThat(item.toString().contains(publicado)).isFalse();
			assertThat(item.toString().contains(oculto)).isFalse();
			if (item.get("characterName").asText().equals("Zuleica Procuravel")) {
				achou = true;
			}
		}
		assertThat(achou).as("publicado deve aparecer na busca").isTrue();
	}
}
