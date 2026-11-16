package com.socialcoisa.rede.eroleplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import tools.jackson.databind.ObjectMapper;

/**
 * O isolamento da ficha no backend — a parte do produto em que privacidade é requisito, não detalhe
 * de tela.
 *
 * <p>O que este teste tranca:</p>
 *
 * <ul>
 * <li>nenhuma rota de ficha responde sem sessão, nem a leitura;</li>
 * <li>quem não participa da campanha não lê modelo, quadro nem ficha, mesmo com o id na mão;</li>
 * <li>jogador não lê a ficha de outro jogador — recebe 403, não uma ficha filtrada, e o grupo vê
 *     apenas o quadro;</li>
 * <li>jogador não define, ajusta nem remove o modelo;</li>
 * <li>a resposta filtrada nunca carrega a chave de um campo que o público não alcança;</li>
 * <li>o identificador de pessoa na rota não serve para descobrir quem tem ficha em outra
 *     campanha.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SheetSecurityTests {

	private static final String JOINED = "joined";

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

	private void defineTemplate(TestAuthHelper.AuthSession master, String campaignId, String idemKey)
			throws Exception {
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/sheets/template")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fields", List.of()))))
			.andExpect(status().isCreated());
	}

	private void join(TestAuthHelper.AuthSession user, String campaignId) throws Exception {
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/join")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "sec-join-" + campaignId + "-" + user.userId()))
			.andExpect(status().isOk());
	}

	private List<Map<String, String>> fullSheet() {
		return List.of(
			Map.of("key", "forca", "value", "12", "visibility", "GAME_MASTER"),
			Map.of("key", "destreza", "value", "12", "visibility", "GAME_MASTER"),
			Map.of("key", "constituicao", "value", "12", "visibility", "GAME_MASTER"),
			Map.of("key", "inteligencia", "value", "12", "visibility", "GAME_MASTER"),
			Map.of("key", "sabedoria", "value", "12", "visibility", "GAME_MASTER"),
			Map.of("key", "carisma", "value", "12", "visibility", "GAME_MASTER"),
			Map.of("key", "nivel", "value", "1", "visibility", "GAME_MASTER"),
			Map.of("key", "pontos_de_vida", "value", "8", "visibility", "GAME_MASTER"),
			Map.of("key", "defesa", "value", "14", "visibility", "GAME_MASTER"),
			Map.of("key", "origem", "value", "Artista", "visibility", "GAME_MASTER"),
			Map.of("key", "historia", "value", "segredo do personagem", "visibility", "PRIVATE"));
	}

	private void fillOwn(TestAuthHelper.AuthSession user, String campaignId, String name, String idemKey)
			throws Exception {
		mockMvc.perform(put("/roleplay/campaigns/" + campaignId + "/sheets/me")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "entries", fullSheet()))))
			.andExpect(status().isOk());
	}

	@Test
	void nenhumaRotaDeFichaRespondeSemSessao() throws Exception {
		var master = authHelper().authenticate("eroleplay-sec-ficha-mestre");
		String campaignId = createCampaign(master, "Ficha Sem Sessao", "sec-create-ficha-sem-sessao");
		defineTemplate(master, campaignId, "sec-template-sem-sessao");

		String base = "/roleplay/campaigns/" + campaignId + "/sheets";

		mockMvc.perform(get(base + "/template")).andExpect(status().isUnauthorized());
		mockMvc.perform(get(base + "/me")).andExpect(status().isUnauthorized());
		mockMvc.perform(get(base + "/board")).andExpect(status().isUnauthorized());
		mockMvc.perform(get(base)).andExpect(status().isUnauthorized());
		mockMvc.perform(get(base + "/" + master.userId())).andExpect(status().isUnauthorized());

		mockMvc.perform(post(base + "/template")
				.header("Idempotency-Key", "sec-template-anon")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fields", List.of()))))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(put(base + "/me")
				.header("Idempotency-Key", "sec-fill-anon")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Anon", "entries", List.of()))))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(put(base + "/template")
				.header("Idempotency-Key", "sec-update-anon")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fields", List.of()))))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete(base + "/template").header("Idempotency-Key", "sec-delete-anon"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void quemNaoParticipaNaoLeNemEscreveFicha() throws Exception {
		var master = authHelper().authenticate("eroleplay-sec-ficha-dono");
		String campaignId = createCampaign(master, "Ficha Fechada", "sec-create-ficha-fechada");
		defineTemplate(master, campaignId, "sec-template-fechada");
		fillOwn(master, campaignId, "Mestre", "sec-fill-mestre");

		var outsider = authHelper().authenticate("eroleplay-sec-ficha-fora");
		String base = "/roleplay/campaigns/" + campaignId + "/sheets";

		assertThat(mockMvc.perform(get(base + "/template").header("X-Session-Token", outsider.token()))
			.andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(mockMvc.perform(get(base + "/board").header("X-Session-Token", outsider.token()))
			.andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(mockMvc.perform(get(base + "/me").header("X-Session-Token", outsider.token()))
			.andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(mockMvc.perform(get(base + "/" + master.userId()).header("X-Session-Token", outsider.token()))
			.andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(mockMvc.perform(get(base).header("X-Session-Token", outsider.token()))
			.andReturn().getResponse().getStatus()).isEqualTo(403);

		MvcResult write = mockMvc.perform(put(base + "/me")
				.header("X-Session-Token", outsider.token())
				.header("Idempotency-Key", "sec-fill-fora")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Intruso", "entries", fullSheet()))))
			.andReturn();
		assertThat(write.getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void oJogadorNaoLeAFichaDeOutroJogador() throws Exception {
		var master = authHelper().authenticate("eroleplay-sec-dois-mestre");
		String campaignId = createCampaign(master, "Dois Jogadores", "sec-create-dois");
		defineTemplate(master, campaignId, "sec-template-dois");

		var primeiro = authHelper().authenticate("eroleplay-sec-jogador-um");
		var segundo = authHelper().authenticate("eroleplay-sec-jogador-dois");
		join(primeiro, campaignId);
		join(segundo, campaignId);

		fillOwn(primeiro, campaignId, "Primeiro", "sec-fill-um");
		fillOwn(segundo, campaignId, "Segundo", "sec-fill-dois");

		// O vizinho de mesa é jogador: a ficha do outro não é um recurso que ele alcança.
		MvcResult blocked = mockMvc.perform(
				get("/roleplay/campaigns/" + campaignId + "/sheets/" + primeiro.userId())
					.header("X-Session-Token", segundo.token()))
			.andReturn();
		assertThat(blocked.getResponse().getStatus()).isEqualTo(403);
		assertThat(blocked.getResponse().getContentAsString()).contains("FORBIDDEN");
		assertThat(blocked.getResponse().getContentAsString()).doesNotContain("segredo do personagem");

		// E o quadro, que ele alcança, não carrega campo fechado.
		MvcResult board = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/sheets/board")
				.header("X-Session-Token", segundo.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(board.getResponse().getContentAsString()).doesNotContain("historia")
			.doesNotContain("segredo do personagem");
	}

	@Test
	void oJogadorNaoAdministraOModeloDeFicha() throws Exception {
		var master = authHelper().authenticate("eroleplay-sec-modelo-mestre");
		String campaignId = createCampaign(master, "Modelo do Mestre", "sec-create-modelo-mestre");
		defineTemplate(master, campaignId, "sec-template-modelo");

		var player = authHelper().authenticate("eroleplay-sec-modelo-jogador");
		join(player, campaignId);
		String base = "/roleplay/campaigns/" + campaignId + "/sheets";

		MvcResult update = mockMvc.perform(put(base + "/template")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "sec-update-jogador")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fields",
					List.of(Map.of("key", "historia", "maxVisibility", "CAMPAIGN"))))))
			.andReturn();
		assertThat(update.getResponse().getStatus()).isEqualTo(403);

		MvcResult removal = mockMvc.perform(delete(base + "/template")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "sec-delete-jogador"))
			.andReturn();
		assertThat(removal.getResponse().getStatus()).isEqualTo(403);

		// O modelo continua como o mestre o deixou.
		MvcResult template = mockMvc.perform(get(base + "/template").header("X-Session-Token", player.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(template.getResponse().getContentAsString()).contains("\"key\":\"historia\"");
	}

	@Test
	void oJogadorNaoListaAsFichasDoGrupo() throws Exception {
		var master = authHelper().authenticate("eroleplay-sec-lista-mestre");
		String campaignId = createCampaign(master, "Lista Fechada", "sec-create-lista");
		defineTemplate(master, campaignId, "sec-template-lista");

		var player = authHelper().authenticate("eroleplay-sec-lista-jogador");
		join(player, campaignId);

		MvcResult blocked = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/sheets")
				.header("X-Session-Token", player.token()))
			.andReturn();
		assertThat(blocked.getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void modeloDuplicadoERecusadoERemocaoComFichaTambem() throws Exception {
		var master = authHelper().authenticate("eroleplay-sec-duplicado-mestre");
		String campaignId = createCampaign(master, "Modelo Duplicado", "sec-create-duplicado");
		defineTemplate(master, campaignId, "sec-template-duplicado");

		MvcResult again = mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/sheets/template")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "sec-template-duplicado-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fields", List.of()))))
			.andReturn();
		assertThat(again.getResponse().getStatus()).isEqualTo(409);
		assertThat(again.getResponse().getContentAsString()).contains("SHEET_TEMPLATE_EXISTS");
	}

	@Test
	void oIdentificadorDePessoaNaoRevelaFichaDeOutraCampanha() throws Exception {
		var master = authHelper().authenticate("eroleplay-sec-cruzado-mestre");
		String campaignId = createCampaign(master, "Campanha Aberta", "sec-create-cruzado");
		defineTemplate(master, campaignId, "sec-template-cruzado");

		// Pessoa de uma segunda campanha, que não participa desta.
		var outroMestre = authHelper().authenticate("eroleplay-sec-cruzado-outro");
		String outraCampanha = createCampaign(outroMestre, "Outra Campanha", "sec-create-cruzado-outro");
		defineTemplate(outroMestre, outraCampanha, "sec-template-cruzado-outro");
		fillOwn(outroMestre, outraCampanha, "Forasteiro", "sec-fill-cruzado-outro");

		// Com o id da pessoa na mão, mas sem participar desta campanha, o mestre desta não a lê...
		MvcResult crossCampaign = mockMvc.perform(
				get("/roleplay/campaigns/" + campaignId + "/sheets/" + outroMestre.userId())
					.header("X-Session-Token", master.token()))
			.andReturn();
		assertThat(crossCampaign.getResponse().getStatus()).isEqualTo(404);
		assertThat(crossCampaign.getResponse().getContentAsString()).doesNotContain("Forasteiro");

		// ...e um id inventado responde igual, para não revelar quem tem ficha.
		MvcResult invented = mockMvc.perform(
				get("/roleplay/campaigns/" + campaignId + "/sheets/usuario-que-nao-existe")
					.header("X-Session-Token", master.token()))
			.andReturn();
		assertThat(invented.getResponse().getStatus()).isEqualTo(404);
	}
}