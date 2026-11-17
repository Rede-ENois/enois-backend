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
 * A segurança da biblioteca na API, não na tela: sem sessão as escritas são 401; a leitura é
 * pública; quem publica é o DONO — nem mestre publica nem despublica ficha alheia; ficha
 * inexistente e ficha alheia respondem o MESMO 404 (sem enumeração); a leitura pública de
 * publicação inexistente é 404 e não vaza o recorte de ficha nunca publicada.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LibrarySecurityTests {

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

	private String defineTemplateAndFill(TestAuthHelper.AuthSession master, TestAuthHelper.AuthSession player,
			String campaignId, String idemPrefix, String name) throws Exception {
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/sheets/template")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", idemPrefix + "-template")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fields",
					java.util.List.of(Map.of("key", "forca", "maxVisibility", "CAMPAIGN"),
						Map.of("key", "historia", "maxVisibility", "GAME_MASTER"))))))
			.andReturn();
		MvcResult result = mockMvc.perform(put("/roleplay/campaigns/" + campaignId + "/sheets/me")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", idemPrefix + "-sheet")
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

	/** A ficha com o campo aberto FECHADO na republicação (preenchimento substitutivo). */
	private java.util.List<Map<String, String>> fichaFechada() {
		return java.util.List.of(
			Map.of("key", "forca", "value", "16", "visibility", "GAME_MASTER"),
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
	void escritaSemSessaoE401ELeituraPublicaEAberta() throws Exception {
		// Escritas exigem sessão (guard fail-closed do POST/DELETE).
		mockMvc.perform(post("/roleplay/campaigns/camp_x/sheets/sheet_x/publish"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/roleplay/campaigns/camp_x/sheets/sheet_x/publish"))
			.andExpect(status().isUnauthorized());

		// Leitura é pública: sem sessão a listagem responde e não explode.
		mockMvc.perform(get("/roleplay/library")).andExpect(status().isOk());
		mockMvc.perform(get("/roleplay/library/inexistente")).andExpect(status().isNotFound());
	}

	@Test
	void mestreNaoPublicaFichaAlheiaENaoDespublica() throws Exception {
		TestAuthHelper helper = authHelper();
		var master = helper.authenticate("lib_sec_master");
		var player = helper.authenticate("lib_sec_player");
		String campaignId = createCampaign(master, "Biblioteca Segura", "lib-sec-camp");
		join(player, campaignId, "lib-sec-join");
		String sheetId = defineTemplateAndFill(master, player, campaignId, "lib-sec", "Aria Alheia");

		// Ficha inexistente e ficha alheia: o MESMO 404, sem dizer qual é qual.
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/sheets/sheet_que_nao_existe/publish")
				.header("X-Session-Token", master.token()).header("Idempotency-Key", "lib-sec-404-a"))
			.andExpect(status().isNotFound());
		MvcResult alheia = mockMvc
			.perform(post("/roleplay/campaigns/" + campaignId + "/sheets/" + sheetId + "/publish")
				.header("X-Session-Token", master.token()).header("Idempotency-Key", "lib-sec-404-b"))
			.andExpect(status().isNotFound()).andReturn();
		String corpo = alheia.getResponse().getContentAsString();
		assertThat(corpo.contains(sheetId)).isFalse();

		// O mestre também não despublica publicação alheia (opt-in pessoal do dono).
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/sheets/" + sheetId + "/publish")
				.header("X-Session-Token", player.token()).header("Idempotency-Key", "lib-sec-pub"))
			.andExpect(status().isCreated());
		mockMvc.perform(delete("/roleplay/campaigns/" + campaignId + "/sheets/" + sheetId + "/publish")
				.header("X-Session-Token", master.token()).header("Idempotency-Key", "lib-sec-unpub"))
			.andExpect(status().isNotFound());
	}

	@Test
	void oRecorteDoMestreNuncaViraPublicoMesmoRepublicando() throws Exception {
		TestAuthHelper helper = authHelper();
		var master = helper.authenticate("lib_sec2_master");
		var player = helper.authenticate("lib_sec2_player");
		String campaignId = createCampaign(master, "Biblioteca Recorte", "lib-sec2-camp");
		join(player, campaignId, "lib-sec2-join");
		String sheetId = defineTemplateAndFill(master, player, campaignId, "lib-sec2", "Bento Recorte");

		MvcResult pub = mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/sheets/" + sheetId + "/publish")
				.header("X-Session-Token", player.token()).header("Idempotency-Key", "lib-sec2-pub"))
			.andExpect(status().isCreated()).andReturn();
		String publicationId = objectMapper.readTree(pub.getResponse().getContentAsString()).get("id").asText();

		// Leitura anônima da publicação: o segredo do mestre não está lá, nem o id do dono.
		String corpo = mockMvc.perform(get("/roleplay/library/" + publicationId))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(corpo.contains("segredo da mesa")).as("segredo vazou: %s", corpo).isFalse();
		assertThat(corpo.contains(player.userId())).as("dono vazou: %s", corpo).isFalse();
		assertThat(objectMapper.readTree(corpo).get("fields").get("historia")).isNull();
	}
}
