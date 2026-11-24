package com.socialcoisa.rede.eroleplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Tag;
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
 * A mesa não vaza para quem não joga nela.
 *
 * <p>O que este teste tranca: sem sessão a mesa responde 401 (as escritas passam pelo mesmo
 * guarda fail-closed que o resto da plataforma); quem não participa da campanha não lê estado nem
 * tokens, não escreve cena nem abre a voz; escrita de mesa é do mestre — jogador não vira mestre
 * por clique; token de outro id de mesa não é movido nem removido por rotas desta mesa (IDOR);
 * e mesas distintas não compartilham estado.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("critico")
class TableSecurityTests {

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
					"description", "campanha de teste de seguranca da mesa",
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
				.content(objectMapper.writeValueAsString(Map.of("name", "Mesa Fechada", "open", true))))
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

	@Test
	void semSessaoNaoLeNemEscreveNaMesa() throws Exception {
		var master = authHelper().authenticate("f6-seg-mestre");
		String campaignId = createCampaign(master, "Mesa Sem Sessao", "f6-create-seg-sessao");
		String tableId = createTable(master, campaignId, "f6-table-seg-sessao");
		String base = "/roleplay/campaigns/" + campaignId + "/tables/" + tableId;

		mockMvc.perform(get(base + "/state")).andExpect(status().isUnauthorized());
		mockMvc.perform(post(base + "/state/scene")
				.header("Idempotency-Key", "f6-seg-anonimo")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"scene\":\"invasao\"}"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post(base + "/voice")
				.header("Idempotency-Key", "f6-seg-anonimo-voz")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void visitanteForaDaCampanhaNaoVeNemMudaAMesa() throws Exception {
		var master = authHelper().authenticate("f6-seg-mestre-visit");
		var visitante = authHelper().authenticate("f6-seg-visitante");
		String campaignId = createCampaign(master, "Mesa Fechada Ao Visitante", "f6-create-seg-visit");
		String tableId = createTable(master, campaignId, "f6-table-seg-visit");
		String base = "/roleplay/campaigns/" + campaignId + "/tables/" + tableId;

		mockMvc.perform(get(base + "/state").header("X-Session-Token", visitante.token()))
			.andExpect(status().isForbidden())
			.andExpect(result -> assertThat(result.getResponse().getContentAsString())
				.contains("NOT_CAMPAIGN_MEMBER"));
		mockMvc.perform(get(base + "/tokens").header("X-Session-Token", visitante.token()))
			.andExpect(status().isForbidden());

		MvcResult cena = mockMvc.perform(post(base + "/state/scene")
				.header("X-Session-Token", visitante.token())
				.header("Idempotency-Key", "f6-seg-visit-cena")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"scene\":\"a sala deles\"}"))
			.andReturn();
		assertThat(cena.getResponse().getStatus()).isEqualTo(403);

		MvcResult ordem = mockMvc.perform(post(base + "/state/turn-order")
				.header("X-Session-Token", visitante.token())
				.header("Idempotency-Key", "f6-seg-visit-ordem")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"order\":[\"" + visitante.userId() + "\"]}"))
			.andReturn();
		assertThat(ordem.getResponse().getStatus()).isEqualTo(403);

		MvcResult voz = mockMvc.perform(post(base + "/voice")
				.header("X-Session-Token", visitante.token())
				.header("Idempotency-Key", "f6-seg-visit-voz")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andReturn();
		assertThat(voz.getResponse().getStatus()).isEqualTo(403);

		// A mesa do mestre segue intocada.
		JsonNode estado = objectMapper.readTree(mockMvc
			.perform(get(base + "/state").header("X-Session-Token", master.token()))
			.andReturn().getResponse().getContentAsString());
		assertThat(estado.get("scene").isNull()).isTrue();
		assertThat(estado.get("turnOrder").size()).isZero();
	}

	@Test
	void jogadorNaoFazOTrabalhoDoMestre() throws Exception {
		var master = authHelper().authenticate("f6-seg-mestre-role");
		var player = authHelper().authenticate("f6-seg-jogador-role");
		String campaignId = createCampaign(master, "Mesa Papel", "f6-create-seg-role");
		String tableId = createTable(master, campaignId, "f6-table-seg-role");
		join(player, campaignId, "f6-join-seg-role");
		String base = "/roleplay/campaigns/" + campaignId + "/tables/" + tableId;

		MvcResult ordem = mockMvc.perform(post(base + "/state/turn-order")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "f6-seg-role-ordem")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"order\":[\"" + player.userId() + "\"]}"))
			.andReturn();
		assertThat(ordem.getResponse().getStatus()).isEqualTo(403);

		MvcResult cena = mockMvc.perform(post(base + "/state/scene")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "f6-seg-role-cena")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"scene\":\"cena do jogador\"}"))
			.andReturn();
		assertThat(cena.getResponse().getStatus()).isEqualTo(403);

		// Token de cenário não nasce do jogador.
		MvcResult cenario = mockMvc.perform(post(base + "/tokens")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "f6-seg-role-cenario")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"label\":\"monstro\", \"color\":\"#FF0000\", \"posX\":0, \"posY\":0}"))
			.andReturn();
		assertThat(cenario.getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void jogadorNaoAbreNemConsultaAVozDaMesa() throws Exception {
		var master = authHelper().authenticate("f6-seg-mestre-voz");
		var player = authHelper().authenticate("f6-seg-jogador-voz");
		String campaignId = createCampaign(master, "Mesa Voz", "f6-create-seg-voz");
		String tableId = createTable(master, campaignId, "f6-table-seg-voz");
		join(player, campaignId, "f6-join-seg-voz");
		String base = "/roleplay/campaigns/" + campaignId + "/tables/" + tableId;

		// A voz é estado da mesa: quem muda cena e ordem é o mestre, e abrir/fechar a sala de voz
		// também é — o jogador não convoca a mesa nem a encerra para todos.
		MvcResult abrir = mockMvc.perform(post(base + "/voice")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "f6-seg-voz-jogador")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andReturn();
		assertThat(abrir.getResponse().getStatus()).isEqualTo(403);

		// A leitura do "ao vivo" é tolerante, como toda leitura pública: sem sala aberta (o POST do
		// jogador foi recusado), o participante lê vazio em vez de erro — a recusa é do ato, não da
		// leitura, e a entrada segue sendo recusada no join.
		MvcResult consulta = mockMvc.perform(get(base + "/voice/open")
				.header("X-Session-Token", player.token()))
			.andReturn();
		assertThat(consulta.getResponse().getStatus()).isEqualTo(200);
		assertThat(consulta.getResponse().getContentAsString()).isEqualTo("");

		// A mesa do mestre segue sem sala de voz criada pelo jogador.
		JsonNode mesas = objectMapper.readTree(mockMvc
			.perform(get("/roleplay/campaigns/" + campaignId + "/tables")
				.header("X-Session-Token", master.token()))
			.andReturn().getResponse().getContentAsString());
		// O campo da listagem é o id da sala: nulo = ninguém criou sala pela mão do jogador.
		assertThat(mesas.get("items").get(0).get("voiceRoomId").isNull()).isTrue();
	}

	@Test
	void vozDeMesaDeOutraCampanhaNaoEConfirmadaPeloPathDesta() throws Exception {
		var master = authHelper().authenticate("f6-seg-mestre-voz-ida");
		String campaignId = createCampaign(master, "Mesa Voz Cruzada", "f6-create-seg-voz-ida");
		String tableAqui = createTable(master, campaignId, "f6-table-seg-voz-ida");

		// O mestre abre a voz da mesa desta campanha (a rota dele, o dono dela).
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/tables/" + tableAqui + "/voice")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f6-seg-voz-ida-abrir")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andReturn();

		// O path de OUTRA campanha não confirma a chamada da mesa alheia: quem participa das duas
		// campanhas não usa o caminho de uma para ler a voz da mesa da outra — mesa de outra
		// campanha é 404, como se não existisse por aqui.
		String campaignB = createCampaign(master, "Mesa Voz Outra", "f6-create-seg-voz-outra");
		String baseB = "/roleplay/campaigns/" + campaignB + "/tables/" + tableAqui;
		MvcResult consulta = mockMvc.perform(get(baseB + "/voice/open")
				.header("X-Session-Token", master.token()))
			.andReturn();
		assertThat(consulta.getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void tokenDeOutraMesaNaoEMovidoPorRotaDesta() throws Exception {
		var master = authHelper().authenticate("f6-seg-mestre-idor");
		String campaignId = createCampaign(master, "Mesa IDOR", "f6-create-seg-idor");
		String tableA = createTable(master, campaignId, "f6-table-seg-idor-a");
		String tableB = createTable(master, campaignId, "f6-table-seg-idor-b");
		String baseA = "/roleplay/campaigns/" + campaignId + "/tables/" + tableA + "/tokens";
		String baseB = "/roleplay/campaigns/" + campaignId + "/tables/" + tableB + "/tokens";

		JsonNode criado = objectMapper.readTree(mockMvc
			.perform(post(baseA)
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f6-seg-idor-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("label", "baú", "color", "#C0C0C0", "posX", 1,
					"posY", 1))))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		String tokenId = criado.get("id").asText();

		// O token mora na mesa A; a rota da mesa B não o encontra (mesma campanha, outra mesa).
		MvcResult mover = mockMvc.perform(post(baseB + "/" + tokenId + "/move")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f6-seg-idor-move")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"posX\":8, \"posY\":8}"))
			.andReturn();
		assertThat(mover.getResponse().getStatus()).isEqualTo(404);
		assertThat(mover.getResponse().getContentAsString()).contains("TOKEN_NOT_FOUND");

		// E a lista da mesa B continua vazia: a mesa A não vaza para a B.
		MvcResult lista = mockMvc.perform(get(baseB).header("X-Session-Token", master.token()))
			.andReturn();
		assertThat(objectMapper.readTree(lista.getResponse().getContentAsString()).size()).isZero();
	}

	@Test
	void mesaInexistenteNaoAbreNemResponde() throws Exception {
		var master = authHelper().authenticate("f6-seg-mestre-404");
		String campaignId = createCampaign(master, "Mesa 404", "f6-create-seg-404");
		String base = "/roleplay/campaigns/" + campaignId + "/tables/mesa_nao_existe";

		mockMvc.perform(get(base + "/state").header("X-Session-Token", master.token()))
			.andExpect(status().isNotFound())
			.andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("TABLE_NOT_FOUND"));
	}
}
