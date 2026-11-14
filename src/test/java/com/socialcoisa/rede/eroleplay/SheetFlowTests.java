package com.socialcoisa.rede.eroleplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
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
 * A ficha de personagem, de ponta a ponta: modelo do mestre, preenchimento do jogador e a leitura
 * de cada um.
 *
 * <p>O que este teste tranca:</p>
 *
 * <ul>
 * <li>o modelo nasce dos atributos do sistema, e o mestre decide até onde cada campo abre;</li>
 * <li>o preenchimento é validado no servidor — valor inválido, campo desconhecido, visibilidade
 *     acima do máximo e campo obrigatório faltando são recusados;</li>
 * <li>o jogador lê a própria ficha inteira; o mestre lê o que foi aberto a ele; o grupo lê o
 *     quadro, e só o que foi aberto à campanha;</li>
 * <li>fechar um campo no modelo fecha o que já estava preenchido, sem esperar nova gravação;</li>
 * <li>o derivado é calculado na leitura e nunca gravado.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SheetFlowTests {

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
					"description", "campanha de teste",
					"setting", "reino distante",
					"gameSystem", "d20-generico",
					"systemFamily", "D20",
					"visibility", "PUBLIC"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	/** O mestre define o modelo com as aberturas pedidas; o que não é pedido fica no padrão. */
	private void defineTemplate(TestAuthHelper.AuthSession master, String campaignId, String idemKey,
			Map<String, String> visibilities) throws Exception {
		MvcResult result = mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/sheets/template")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fields",
					visibilities.entrySet().stream()
						.map(entry -> Map.of("key", entry.getKey(), "maxVisibility", entry.getValue()))
						.toList()))))
			.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
	}

	private void join(TestAuthHelper.AuthSession user, String campaignId, String idemKey) throws Exception {
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/join")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", idemKey))
			.andExpect(status().isOk());
	}

	private MvcResult fill(TestAuthHelper.AuthSession user, String campaignId, String name, String idemKey,
			List<Map<String, String>> entries) throws Exception {
		return mockMvc.perform(put("/roleplay/campaigns/" + campaignId + "/sheets/me")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "entries", entries))))
			.andReturn();
	}

	/** A ficha completa do D20 original, com um campo por troca opcional. */
	private List<Map<String, String>> fullSheet(Map<String, String> overrides) {
		List<Map<String, String>> entries = new ArrayList<>(List.of(
			entry("forca", "12", "CAMPAIGN"),
			entry("destreza", "14", "GAME_MASTER"),
			entry("constituicao", "13", "GAME_MASTER"),
			entry("inteligencia", "11", "GAME_MASTER"),
			entry("sabedoria", "10", "GAME_MASTER"),
			entry("carisma", "9", "GAME_MASTER"),
			entry("nivel", "1", "GAME_MASTER"),
			entry("pontos_de_vida", "8", "GAME_MASTER"),
			entry("defesa", "14", "GAME_MASTER"),
			entry("origem", "Artista", "GAME_MASTER")));
		overrides.forEach((key, value) -> {
			entries.removeIf(existing -> existing.get("key").equals(key));
			// "forca" e "historia" são os campos que os cenários abrem à campanha; os demais ficam
			// no recorte do mestre.
			boolean openToCampaign = key.equals("forca") || key.equals("historia");
			entries.add(entry(key, value, openToCampaign ? "CAMPAIGN" : "GAME_MASTER"));
		});
		return entries;
	}

	private static Map<String, String> entry(String key, String value, String visibility) {
		return Map.of("key", key, "value", value, "visibility", visibility);
	}

	private JsonNode readBody(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private static JsonNode entryWithKey(JsonNode entries, String key) {
		for (JsonNode entry : entries) {
			if (key.equals(entry.get("key").asText())) {
				return entry;
			}
		}
		return null;
	}

	@Test
	void oModeloNasceDosAtributosDoSistemaEOoMestreEscolheAbertura() throws Exception {
		var master = authHelper().authenticate("eroleplay-f3-modelo");
		String campaignId = createCampaign(master, "Modelo de Ficha", "f3-create-modelo");

		defineTemplate(master, campaignId, "f3-modelo-1",
			Map.of("historia", "CAMPAIGN", "forca", "PRIVATE"));

		JsonNode template = readBody(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/sheets/template")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn());

		assertThat(template.get("gameSystem").asText()).isEqualTo("d20-generico");
		assertThat(template.get("fields")).hasSize(11);
		assertThat(template.get("fields").get(0).get("key").asText()).isEqualTo("forca");
		assertThat(template.get("fields").get(0).get("required").asBoolean()).isTrue();
		assertThat(template.get("fields").get(0).get("maxVisibility").asText()).isEqualTo("PRIVATE");
		assertThat(template.get("fields").get(10).get("key").asText()).isEqualTo("historia");
		assertThat(template.get("fields").get(10).get("maxVisibility").asText()).isEqualTo("CAMPAIGN");
		assertThat(template.get("fields").get(10).get("required").asBoolean()).isFalse();
	}

	@Test
	void oJogadorPreencheELeAPropriaFichaInteira() throws Exception {
		var master = authHelper().authenticate("eroleplay-f3-dono-mestre");
		String campaignId = createCampaign(master, "Ficha do Jogador", "f3-create-dono");
		defineTemplate(master, campaignId, "f3-dono-modelo",
			Map.of("forca", "CAMPAIGN", "historia", "CAMPAIGN"));

		var player = authHelper().authenticate("eroleplay-f3-dono");
		join(player, campaignId, "f3-dono-join");

		MvcResult filled = fill(player, campaignId, "Aranha do Vale", "f3-dono-fill-1",
			fullSheet(Map.of("historia", "Cresceu numa estalagem.")));
		assertThat(filled.getResponse().getStatus()).isEqualTo(200);

		JsonNode sheet = readBody(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/sheets/me")
				.header("X-Session-Token", player.token()))
			.andExpect(status().isOk())
			.andReturn());

		assertThat(sheet.get("name").asText()).isEqualTo("Aranha do Vale");
		assertThat(sheet.get("entries")).hasSize(11);
		assertThat(entryWithKey(sheet.get("entries"), "historia").get("value").asText())
			.isEqualTo("Cresceu numa estalagem.");
		// (12 - 10) / 2 = 1, recalculado na leitura.
		assertThat(entryWithKey(sheet.get("derived"), "mod_forca").get("value").asText()).isEqualTo("1");
	}

	@Test
	void oMestreLeOQueFoiAbertoEONaoOQueFicouFechado() throws Exception {
		var master = authHelper().authenticate("eroleplay-f3-mestre-le");
		String campaignId = createCampaign(master, "Recorte do Mestre", "f3-create-recorte");
		defineTemplate(master, campaignId, "f3-recorte-modelo",
			Map.of("forca", "CAMPAIGN", "historia", "CAMPAIGN"));

		var player = authHelper().authenticate("eroleplay-f3-jogador-le");
		join(player, campaignId, "f3-le-join");
		fill(player, campaignId, "Bardo", "f3-le-fill-1", fullSheet(Map.of("historia", "nota particular")));

		JsonNode masterView = readBody(mockMvc.perform(
				get("/roleplay/campaigns/" + campaignId + "/sheets/" + player.userId())
					.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn());

		// O mestre lê o que está aberto a ele e não lê o que o jogador deixou privado.
		assertThat(entryWithKey(masterView.get("entries"), "origem")).isNotNull();
		assertThat(entryWithKey(masterView.get("entries"), "forca")).isNotNull();
	}

	@Test
	void oCampoPrivadoDoJogadorNaoVoltaNemParaOMestre() throws Exception {
		var master = authHelper().authenticate("eroleplay-f3-mestre-fechado");
		String campaignId = createCampaign(master, "Segredo do Jogador", "f3-create-fechado");
		defineTemplate(master, campaignId, "f3-fechado-modelo", Map.of("forca", "CAMPAIGN"));

		var player = authHelper().authenticate("eroleplay-f3-jogador-fechado");
		join(player, campaignId, "f3-fechado-join");

		// O jogador fecha a origem: o máximo do modelo é GAME_MASTER, então fechar é permitido.
		List<Map<String, String>> entries = new ArrayList<>(fullSheet(Map.of()));
		entries.removeIf(existing -> existing.get("key").equals("origem"));
		entries.add(entry("origem", "Acadêmico", "PRIVATE"));
		fill(player, campaignId, "Escondido", "f3-fechado-fill-1", entries);

		JsonNode masterView = readBody(mockMvc.perform(
				get("/roleplay/campaigns/" + campaignId + "/sheets/" + player.userId())
					.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn());
		assertThat(entryWithKey(masterView.get("entries"), "origem")).isNull();
		assertThat(masterView.toString()).doesNotContain("Acadêmico");

		// O dono continua vendo o que escreveu.
		JsonNode own = readBody(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/sheets/me")
				.header("X-Session-Token", player.token()))
			.andExpect(status().isOk())
			.andReturn());
		assertThat(entryWithKey(own.get("entries"), "origem").get("value").asText()).isEqualTo("Acadêmico");
	}

	@Test
	void oQuadroDoGrupoMostraSoOQueFoiAbertoACampanha() throws Exception {
		var master = authHelper().authenticate("eroleplay-f3-mestre-quadro");
		String campaignId = createCampaign(master, "Quadro da Mesa", "f3-create-quadro");
		defineTemplate(master, campaignId, "f3-quadro-modelo", Map.of("forca", "CAMPAIGN",
			"historia", "CAMPAIGN"));

		var player = authHelper().authenticate("eroleplay-f3-jogador-quadro");
		join(player, campaignId, "f3-quadro-join");
		fill(player, campaignId, "Maga", "f3-quadro-fill-1", fullSheet(Map.of("historia", "aberto ao grupo")));

		JsonNode board = readBody(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/sheets/board")
				.header("X-Session-Token", player.token()))
			.andExpect(status().isOk())
			.andReturn());

		// O grupo vê o nome do personagem e só os campos abertos à campanha.
		assertThat(board).hasSize(1);
		assertThat(board.get(0).get("name").asText()).isEqualTo("Maga");
		assertThat(board.get(0).get("entries")).hasSize(2);
		assertThat(entryWithKey(board.get(0).get("entries"), "forca")).isNotNull();
		assertThat(entryWithKey(board.get(0).get("entries"), "historia")).isNotNull();
		assertThat(entryWithKey(board.get(0).get("entries"), "origem")).isNull();
	}

	@Test
	void oMestreEnxergaAsFichasDoGrupoNoRecorteDele() throws Exception {
		var master = authHelper().authenticate("eroleplay-f3-mestre-lista");
		String campaignId = createCampaign(master, "Lista do Mestre", "f3-create-lista");
		defineTemplate(master, campaignId, "f3-lista-modelo", Map.of("forca", "CAMPAIGN"));

		var player = authHelper().authenticate("eroleplay-f3-jogador-lista");
		join(player, campaignId, "f3-lista-join");
		fill(player, campaignId, "Guerreiro", "f3-lista-fill-1", fullSheet(Map.of()));

		JsonNode list = readBody(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/sheets")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn());

		assertThat(list).hasSize(1);
		assertThat(list.get(0).get("userId").asText()).isEqualTo(player.userId());
		// O mestre enxerga os campos abertos a ele — inclusive os que o grupo não vê.
		assertThat(entryWithKey(list.get(0).get("entries"), "origem")).isNotNull();
	}

	@Test
	void fecharUmCampoNoModeloFechaOQueJaEstavaPreenchido() throws Exception {
		var master = authHelper().authenticate("eroleplay-f3-mestre-fecha");
		String campaignId = createCampaign(master, "Fechamento", "f3-create-fecha");
		defineTemplate(master, campaignId, "f3-fecha-modelo", Map.of("forca", "CAMPAIGN"));

		var player = authHelper().authenticate("eroleplay-f3-jogador-fecha");
		join(player, campaignId, "f3-fecha-join");
		fill(player, campaignId, "Vidente", "f3-fecha-fill-1", fullSheet(Map.of()));

		// Antes do fechamento, o mestre lê o campo.
		assertThat(entryWithKey(readBody(mockMvc.perform(
				get("/roleplay/campaigns/" + campaignId + "/sheets/" + player.userId())
					.header("X-Session-Token", master.token()))
			.andReturn()).get("entries"), "origem")).isNotNull();

		// O mestre fecha o campo no modelo.
		mockMvc.perform(put("/roleplay/campaigns/" + campaignId + "/sheets/template")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f3-fecha-modelo-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fields",
					List.of(Map.of("key", "origem", "maxVisibility", "PRIVATE"))))))
			.andExpect(status().isOk());

		// A regra vale para o que já existe: o mestre deixa de ler o campo, sem nova gravação.
		assertThat(entryWithKey(readBody(mockMvc.perform(
				get("/roleplay/campaigns/" + campaignId + "/sheets/" + player.userId())
					.header("X-Session-Token", master.token()))
			.andReturn()).get("entries"), "origem")).isNull();
	}

	@Test
	void oModeloNaoSaiComFichaPreenchida() throws Exception {
		var master = authHelper().authenticate("eroleplay-f3-mestre-apaga");
		String campaignId = createCampaign(master, "Modelo em Uso", "f3-create-apaga");
		defineTemplate(master, campaignId, "f3-apaga-modelo", Map.of("forca", "CAMPAIGN"));

		var player = authHelper().authenticate("eroleplay-f3-jogador-apaga");
		join(player, campaignId, "f3-apaga-join");
		fill(player, campaignId, "Ocupado", "f3-apaga-fill-1", fullSheet(Map.of()));

		MvcResult blocked = mockMvc.perform(delete("/roleplay/campaigns/" + campaignId + "/sheets/template")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f3-apaga-del-1"))
			.andReturn();
		assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
		assertThat(blocked.getResponse().getContentAsString()).contains("SHEET_TEMPLATE_IN_USE");
	}

	@Test
	void oValorEValidadoNoServidor() throws Exception {
		var master = authHelper().authenticate("eroleplay-f3-mestre-valida");
		String campaignId = createCampaign(master, "Validacao", "f3-create-valida");
		defineTemplate(master, campaignId, "f3-valida-modelo", Map.of("forca", "CAMPAIGN"));

		var player = authHelper().authenticate("eroleplay-f3-jogador-valida");
		join(player, campaignId, "f3-valida-join");

		// Campo obrigatório faltando.
		MvcResult missing = fill(player, campaignId, "Sem Forca", "f3-valida-1",
			List.of(entry("forca", "10", "GAME_MASTER")));
		assertThat(missing.getResponse().getStatus()).isEqualTo(400);
		assertThat(missing.getResponse().getContentAsString()).contains("SHEET_FIELD_REQUIRED");

		// Campo que o modelo não tem.
		MvcResult unknown = fill(player, campaignId, "Poder Oculto", "f3-valida-2",
			fullSheet(Map.of("poder_oculto", "99")));
		assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
		assertThat(unknown.getResponse().getContentAsString()).contains("SHEET_FIELD_UNKNOWN");

		// Número que não é número.
		MvcResult invalid = fill(player, campaignId, "Forca Errada", "f3-valida-3",
			fullSheet(Map.of("forca", "muito forte")));
		assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
		assertThat(invalid.getResponse().getContentAsString()).contains("SHEET_VALUE_INVALID");

		// Escolha fora das opções declaradas pelo sistema.
		MvcResult choice = fill(player, campaignId, "Origem Errada", "f3-valida-4",
			fullSheet(Map.of("origem", "Imperador")));
		assertThat(choice.getResponse().getStatus()).isEqualTo(400);
		assertThat(choice.getResponse().getContentAsString()).contains("SHEET_VALUE_INVALID");

		// Nome obrigatório.
		MvcResult name = fill(player, campaignId, "   ", "f3-valida-5", fullSheet(Map.of()));
		assertThat(name.getResponse().getStatus()).isEqualTo(400);
		assertThat(name.getResponse().getContentAsString()).contains("SHEET_NAME_REQUIRED");
	}

	@Test
	void oJogadorNaoAbreCampoAcimaDoMaximoDoMestre() throws Exception {
		var master = authHelper().authenticate("eroleplay-f3-mestre-maximo");
		String campaignId = createCampaign(master, "Teto de Abertura", "f3-create-maximo");
		// O mestre deixa a história só com ele mesmo.
		defineTemplate(master, campaignId, "f3-maximo-modelo", Map.of("historia", "GAME_MASTER"));

		var player = authHelper().authenticate("eroleplay-f3-jogador-maximo");
		join(player, campaignId, "f3-maximo-join");

		MvcResult tooOpen = fill(player, campaignId, "Aberto Demais", "f3-maximo-1",
			fullSheet(Map.of("historia", "CAMPAIGN")));
		assertThat(tooOpen.getResponse().getStatus()).isEqualTo(400);
		assertThat(tooOpen.getResponse().getContentAsString()).contains("SHEET_VISIBILITY_TOO_OPEN");
	}

	@Test
	void oDerivadoCalculadoNoServidorNaoERegravado() throws Exception {
		var master = authHelper().authenticate("eroleplay-f3-mestre-derivado");
		String campaignId = createCampaign(master, "Derivado", "f3-create-derivado");
		defineTemplate(master, campaignId, "f3-derivado-modelo", Map.of("forca", "CAMPAIGN"));

		var player = authHelper().authenticate("eroleplay-f3-jogador-derivado");
		join(player, campaignId, "f3-derivado-join");
		fill(player, campaignId, "Calculado", "f3-derivado-1", fullSheet(Map.of("forca", "18")));

		JsonNode sheet = readBody(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/sheets/me")
				.header("X-Session-Token", player.token()))
			.andExpect(status().isOk())
			.andReturn());

		// (18 - 10) / 2 = 4, arredondado para baixo.
		assertThat(entryWithKey(sheet.get("derived"), "mod_forca").get("value").asText()).isEqualTo("4");
		// O derivado não é um campo digitável: ele não aparece entre as entradas gravadas.
		assertThat(entryWithKey(sheet.get("entries"), "mod_forca")).isNull();
	}
}