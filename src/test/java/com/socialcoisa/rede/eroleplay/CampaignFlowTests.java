package com.socialcoisa.rede.eroleplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import tools.jackson.databind.ObjectMapper;

/**
 * Ciclo de vida da campanha e da mesa, de ponta a ponta.
 *
 * O que este teste tranca:
 *
 * - criar a campanha coloca quem criou como mestre, e a descoberta pública mostra só a apresentação;
 * - campanha PRIVADA não aceita pedido de entrada — só o convite do mestre cria a associação;
 * - campanha INVITE_ONLY deixa o pedido pendente até a decisão, e a mesma pessoa não fica com dois
 *   pendentes;
 * - aprovar duas vezes não cria duas associações (a segunda decisão recebe 409);
 * - quem não participa não vê a lista de participantes nem as mesas;
 * - o mestre não sai sem transferir o comando; depois de transferir, sai.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampaignFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper authHelper() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	private String createCampaign(TestAuthHelper.AuthSession master, String name, String visibility, String idemKey)
			throws Exception {
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
					"visibility", visibility))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private MvcResult join(TestAuthHelper.AuthSession user, String campaignId) throws Exception {
		return join(user, campaignId, "1");
	}

	/**
	 * Cada clique e um comando novo, entao cada um leva a sua propria chave de idempotencia: a
	 * mesma chave faria o segundo clique ser repeticao do primeiro e devolveria a resposta antiga.
	 */
	private MvcResult join(TestAuthHelper.AuthSession user, String campaignId, String attempt) throws Exception {
		return mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/join")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "join-" + campaignId + "-" + user.userId() + "-" + attempt))
			.andReturn();
	}

	@Test
	void criarColocaOMestreEADescobertaMostraSoAApresentacao() throws Exception {
		var master = authHelper().authenticate("eroleplay-mestre");
		String campaignId = createCampaign(master, "Cronicas do Vale", "PUBLIC", "create-cronicas-vale");

		// Quem cria é mestre e já participa.
		mockMvc.perform(get("/roleplay/campaigns/" + campaignId).header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.viewerRole").value("GAME_MASTER"))
			.andExpect(jsonPath("$.joined").value(true));

		// A descoberta pública mostra a campanha, e quem não participa não vê papel nem entrada.
		mockMvc.perform(get("/roleplay/campaigns").param("query", "Cronicas do Vale"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].slug").value("cronicas-do-vale"))
			.andExpect(jsonPath("$.items[0].viewerRole").doesNotExist());

		// A página da campanha, lida por quem não participa, não diz se ela é pública.
		var outsider = authHelper().authenticate("eroleplay-visitante");
		mockMvc.perform(get("/roleplay/campaigns/" + campaignId).header("X-Session-Token", outsider.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.viewerRole").doesNotExist())
			.andExpect(jsonPath("$.joined").value(false));
	}

	@Test
	void campanhaPrivadaNaoAceitaPedidoDeEntrada() throws Exception {
		var master = authHelper().authenticate("eroleplay-mestre-privada");
		String campaignId = createCampaign(master, "Segredo do Mestre", "PRIVATE", "create-segredo-mestre");

		var player = authHelper().authenticate("eroleplay-jogador-privada");
		MvcResult result = join(player, campaignId);

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(result.getResponse().getContentAsString()).contains("CAMPAIGN_INVITE_ONLY");
	}

	@Test
	void campanhaPorConviteFicaPendenteAteADecisao() throws Exception {
		var master = authHelper().authenticate("eroleplay-mestre-convite");
		String campaignId = createCampaign(master, "Por Convite", "INVITE_ONLY", "create-por-convite");

		var player = authHelper().authenticate("eroleplay-jogador-convite");
		MvcResult result = join(player, campaignId);
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentAsString()).contains("PENDING");

		// O mesmo pedido duas vezes não duplica pendência.
		MvcResult again = join(player, campaignId, "2");
		assertThat(again.getResponse().getStatus()).isEqualTo(409);
		assertThat(again.getResponse().getContentAsString()).contains("REQUEST_ALREADY_PENDING");

		// O mestre vê o pedido pendente.
		MvcResult requests = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/requests")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn();
		String requestId = objectMapper.readTree(requests.getResponse().getContentAsString())
			.get("items").get(0).get("id").asText();

		// Aprovar cria a associação; aprovar de novo não cria outra.
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/requests/" + requestId + "/approve")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "approve-" + requestId))
			.andExpect(status().isOk());

		MvcResult second = mockMvc.perform(
				post("/roleplay/campaigns/" + campaignId + "/requests/" + requestId + "/approve")
					.header("X-Session-Token", master.token())
					.header("Idempotency-Key", "approve-2-" + requestId))
			.andReturn();
		assertThat(second.getResponse().getStatus()).isEqualTo(409);
		assertThat(second.getResponse().getContentAsString()).contains("REQUEST_ALREADY_DECIDED");

		// Agora o jogador participa.
		mockMvc.perform(get("/roleplay/campaigns/" + campaignId).header("X-Session-Token", player.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.viewerRole").value("PLAYER"))
			.andExpect(jsonPath("$.joined").value(true));
	}

	@Test
	void conviteDoMestreCriaAssociacaoNaHora() throws Exception {
		var master = authHelper().authenticate("eroleplay-mestre-convidar");
		String campaignId = createCampaign(master, "Convite Direto", "PRIVATE", "create-convite-direto");

		var player = authHelper().authenticate("eroleplay-convidado");
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/invite")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "invite-" + campaignId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", player.userId()))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/members")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2));
	}

	@Test
	void quemNaoParticipaNaoVeParticipantesNemMesas() throws Exception {
		var master = authHelper().authenticate("eroleplay-mestre-privacidade");
		String campaignId = createCampaign(master, "Privada Total", "PRIVATE", "create-privada-total");

		var outsider = authHelper().authenticate("eroleplay-fora-da-campanha");
		mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/members")
				.header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/tables")
				.header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void oMestrePrecisaTransferirOComandoAntesDeSair() throws Exception {
		var master = authHelper().authenticate("eroleplay-mestre-saida");
		String campaignId = createCampaign(master, "Comando", "PUBLIC", "create-comando");

		var player = authHelper().authenticate("eroleplay-herdeiro");
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/invite")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "invite-herdeiro")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", player.userId()))))
			.andExpect(status().isOk());

		// Sem transferir, o mestre não sai.
		MvcResult blocked = mockMvc.perform(delete("/roleplay/campaigns/" + campaignId + "/members/me")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "leave-master-blocked"))
			.andReturn();
		assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
		assertThat(blocked.getResponse().getContentAsString()).contains("MASTERSHIP_TRANSFER_REQUIRED");

		// Transferindo, o comando passa e o antigo mestre sai como jogador.
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/members/" + player.userId()
				+ "/transfer-master")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "transfer-" + campaignId))
			.andExpect(status().isOk());

		mockMvc.perform(get("/roleplay/campaigns/" + campaignId).header("X-Session-Token", player.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.viewerRole").value("GAME_MASTER"));

		mockMvc.perform(delete("/roleplay/campaigns/" + campaignId + "/members/me")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "leave-master-ok"))
			.andExpect(status().isOk());
	}

	@Test
	void mesaAceitaQuemParticipaERecebeVisitante() throws Exception {
		var master = authHelper().authenticate("eroleplay-mestre-mesa");
		String campaignId = createCampaign(master, "Mesa da Taverna", "PUBLIC", "create-mesa-taverna");

		MvcResult table = mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/tables")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "table-" + campaignId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Mesa da Taverna", "open", true))))
			.andExpect(status().isCreated())
			.andReturn();
		String tableId = objectMapper.readTree(table.getResponse().getContentAsString()).get("id").asText();

		// Quemparticipa senta; a mesa é aberta.
		var player = authHelper().authenticate("eroleplay-jogador-mesa");
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/join")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "join-mesa"))
			.andExpect(status().isOk());

		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/tables/" + tableId + "/sit")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "sit-" + tableId))
			.andExpect(status().isOk());

		mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/tables/" + tableId + "/seats")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2));

		// Quem não participa da campanha não senta.
		var outsider = authHelper().authenticate("eroleplay-visitante-mesa");
		MvcResult blocked = mockMvc.perform(
				post("/roleplay/campaigns/" + campaignId + "/tables/" + tableId + "/sit")
					.header("X-Session-Token", outsider.token())
					.header("Idempotency-Key", "sit-outsider"))
			.andReturn();
		assertThat(blocked.getResponse().getStatus()).isEqualTo(403);
		assertThat(blocked.getResponse().getContentAsString()).contains("NOT_CAMPAIGN_MEMBER");
	}

	@Test
	void campanhaArquivadaSaiDaDescobertaERecusaEntrada() throws Exception {
		var master = authHelper().authenticate("eroleplay-mestre-arquivo");
		String campaignId = createCampaign(master, "Historia Velha", "PUBLIC", "create-historia-velha");

		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/archive")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "archive-" + campaignId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ARCHIVED"));

		mockMvc.perform(get("/roleplay/campaigns").param("query", "Historia Velha"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0));

		var player = authHelper().authenticate("eroleplay-jogador-arquivo");
		MvcResult result = join(player, campaignId);
		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(result.getResponse().getContentAsString()).contains("CAMPAIGN_NOT_ACCEPTING_MEMBERS");
	}

	@Test
	void sistemaDeRegrasDesconhecidoNaoViraD20EmSilencio() throws Exception {
		var master = authHelper().authenticate("eroleplay-mestre-sistema");
		MvcResult result = mockMvc.perform(post("/roleplay/campaigns")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "create-sistema-invalido")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "Sistema Maluco",
					"gameSystem", "d20-generico",
					"systemFamily", "D777"))))
			.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(result.getResponse().getContentAsString()).contains("INVALID_GAME_SYSTEM");
	}

	@Test
	void sistemaNaoRegistradoNaoCriaCampanha() throws Exception {
		var master = authHelper().authenticate("eroleplay-mestre-nao-registrado");
		MvcResult result = mockMvc.perform(post("/roleplay/campaigns")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "create-sistema-nao-registrado")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "Sistema Fantasma",
					"gameSystem", "sistema-que-nao-existe",
					"systemFamily", "D20"))))
			.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(result.getResponse().getContentAsString()).contains("GAME_SYSTEM_NOT_SUPPORTED");
	}

	@Test
	void familiaQueNaoBateComOSistemaRegistradoRecusa() throws Exception {
		var master = authHelper().authenticate("eroleplay-mestre-familia");
		MvcResult result = mockMvc.perform(post("/roleplay/campaigns")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "create-familia-errada")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "Familia Errada",
					"gameSystem", "d20-generico",
					"systemFamily", "D100"))))
			.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(result.getResponse().getContentAsString()).contains("GAME_SYSTEM_FAMILY_MISMATCH");
	}

	@Test
	void aFamiliaVemDoSistemaQuandoOCorpoNaoInforma() throws Exception {
		var master = authHelper().authenticate("eroleplay-mestre-sem-familia");
		MvcResult result = mockMvc.perform(post("/roleplay/campaigns")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "create-sem-familia")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "Sem Familia",
					"gameSystem", "d20-generico"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.systemFamily").value("D20"))
			.andReturn();
		assertThat(result.getResponse().getContentAsString()).contains("d20-generico");
	}

	@Test
	void edicaoDaCampanhaEDoMestre() throws Exception {
		var master = authHelper().authenticate("eroleplay-mestre-edicao");
		String campaignId = createCampaign(master, "Nome Antigo", "PUBLIC", "create-nome-antigo");

		mockMvc.perform(patch("/roleplay/campaigns/" + campaignId)
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "patch-" + campaignId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "Nome Novo",
					"setting", "planicie do sul"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Nome Novo"))
			.andExpect(jsonPath("$.setting").value("planicie do sul"));
	}
}
