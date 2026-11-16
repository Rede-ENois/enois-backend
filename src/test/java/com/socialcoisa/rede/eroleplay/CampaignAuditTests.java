package com.socialcoisa.rede.eroleplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * A trilha de auditoria do Éroleplay na trilha única do sistema (RFC §7).
 *
 * <p>O que este teste tranca:
 *
 * <ul>
 * <li>os atos de campanha (criação, entrada, convite, decisão de pedido, transferência de
 * mestria, arquivamento, criação de mesa) ficam gravados em {@code audit_logs} com ação
 * {@code ROLEPLAY_*}, e o metadata traz quem foi o alvo sem levar conteúdo da campanha;</li>
 * <li>a leitura administrativa devolve o histórico do mais novo para o mais antigo, com teto
 * de lote;</li>
 * <li>a leitura é exclusiva de administrador: sem sessão é 401, sessão comum é 403, e campanha
 * desconhecida é 404 — não histórico vazio.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampaignAuditTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper authHelper() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	private String createCampaign(TestAuthHelper.AuthSession master, String name, String visibility,
			String idemKey) throws Exception {
		MvcResult result = mockMvc.perform(post("/roleplay/campaigns")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", name,
					"gameSystem", "d20-generico",
					"systemFamily", "D20",
					"visibility", visibility))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	/** As ações da trilha da campanha, na ordem em que o endpoint devolve (mais novo primeiro). */
	private List<String> acoes(String token, String campaignId) throws Exception {
		MvcResult result = mockMvc
			.perform(get("/admin/roleplay/campaigns/" + campaignId + "/history")
				.header("X-Session-Token", token))
			.andExpect(status().isOk())
			.andReturn();
		List<String> actions = new ArrayList<>();
		objectMapper.readTree(result.getResponse().getContentAsString())
			.forEach(item -> actions.add(item.get("action").asText()));
		return actions;
	}

	@Test
	void atosDeCampanhaFicamNaTrilhaUnicaDoMaisNovoParaOMaisAntigo() throws Exception {
		var master = authHelper().authenticate("eroleplay-auditoria-mestre");
		String campaignId = createCampaign(master, "Campanha Auditada", "PUBLIC", "audit-create");

		// A ordem do histórico é a prova: cada ato se separa do seguinte no relógio para o
		// timestamp não empatar dentro da mesma microssegundo do banco.
		Thread.sleep(5);
		var player = authHelper().authenticate("eroleplay-auditoria-jogador");
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/join")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "audit-join"))
			.andExpect(status().isOk());

		Thread.sleep(5);
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/tables")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "audit-table")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Mesa Auditada", "open", true))))
			.andExpect(status().isCreated());

		Thread.sleep(5);
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/archive")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "audit-archive"))
			.andExpect(status().isOk());

		var admin = authHelper().authenticate("eroleplay-auditoria-leitor");
		TestDatabase.setAdmin(admin.userId());

		// Do mais novo para o mais antigo: o arquivamento é o primeiro, a criação o último.
		assertThat(acoes(admin.token(), campaignId)).containsExactly(
			"ROLEPLAY_CAMPAIGN_ARCHIVED", "ROLEPLAY_TABLE_CREATED", "ROLEPLAY_CAMPAIGN_MEMBER_JOINED",
			"ROLEPLAY_CAMPAIGN_CREATED");

		// O teto inferior do lote: limit=0 vira 1, e o 1 é o mais novo.
		mockMvc.perform(get("/admin/roleplay/campaigns/" + campaignId + "/history")
				.header("X-Session-Token", admin.token())
				.param("limit", "0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].action").value("ROLEPLAY_CAMPAIGN_ARCHIVED"));
	}

	@Test
	void metadataDaTrilhaDizQuemEMasNaoLevaConteudoDaCampanha() throws Exception {
		var master = authHelper().authenticate("eroleplay-auditoria-meta-mestre");
		MvcResult criada = mockMvc.perform(post("/roleplay/campaigns")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "audit-meta-create")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "Trilha Com Metadata",
					"description", "segredo-narrativo-confidencial-9f2",
					"setting", "ambientacao-secreta-4a7b",
					"gameSystem", "d20-generico",
					"systemFamily", "D20",
					"visibility", "INVITE_ONLY"))))
			.andExpect(status().isCreated())
			.andReturn();
		String campaignId = objectMapper.readTree(criada.getResponse().getContentAsString())
			.get("id").asText();

		var player = authHelper().authenticate("eroleplay-auditoria-meta-jogador");
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/join")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "audit-meta-join"))
			.andExpect(status().isOk());

		MvcResult requests = mockMvc
			.perform(get("/roleplay/campaigns/" + campaignId + "/requests")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn();
		String requestId = objectMapper.readTree(requests.getResponse().getContentAsString())
			.get("items").get(0).get("id").asText();

		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/requests/" + requestId + "/approve")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "audit-meta-approve"))
			.andExpect(status().isOk());

		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/members/" + player.userId()
				+ "/transfer-master")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "audit-meta-transfer"))
			.andExpect(status().isOk());

		var admin = authHelper().authenticate("eroleplay-auditoria-meta-leitor");
		TestDatabase.setAdmin(admin.userId());
		MvcResult history = mockMvc
			.perform(get("/admin/roleplay/campaigns/" + campaignId + "/history")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andReturn();

		String body = history.getResponse().getContentAsString();
		assertThat(body).contains("ROLEPLAY_CAMPAIGN_JOIN_REQUESTED")
			.contains("ROLEPLAY_CAMPAIGN_JOIN_REQUEST_APPROVED")
			.contains("ROLEPLAY_CAMPAIGN_MASTERSHIP_TRANSFERRED")
			// O metadata nomeia os envolvidos (quem pediu, quem aprovou, para quem foi o comando);
			// no corpo da resposta o JSON vem escapado, entao a assercao e pelos identificadores.
			.contains(requestId)
			.contains(player.userId())
			// O conteúdo livre da campanha nunca entra na trilha — só o nome/slug identificam.
			.doesNotContain("segredo-narrativo-confidencial-9f2")
			.doesNotContain("ambientacao-secreta-4a7b");
	}

	@Test
	void conviteERemocaoDeParticipanteFicamNaTrilha() throws Exception {
		var master = authHelper().authenticate("eroleplay-auditoria-convite-mestre");
		String campaignId = createCampaign(master, "Trilha De Convite", "PRIVATE", "audit-convite-create");

		var player = authHelper().authenticate("eroleplay-auditoria-convite-jogador");
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/invite")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "audit-convite-invite")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", player.userId()))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/members/" + player.userId() + "/transfer-master")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "audit-convite-transfer"))
			.andExpect(status().isOk());
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/members/" + master.userId() + "/transfer-master")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "audit-convite-transfer2"))
			.andExpect(status().isOk());

		mockMvc.perform(delete("/roleplay/campaigns/" + campaignId + "/members/" + player.userId())
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "audit-convite-remove"))
			.andExpect(status().isOk());

		var admin = authHelper().authenticate("eroleplay-auditoria-convite-leitor");
		TestDatabase.setAdmin(admin.userId());
		assertThat(acoes(admin.token(), campaignId)).contains(
			"ROLEPLAY_CAMPAIGN_MEMBER_INVITED", "ROLEPLAY_CAMPAIGN_MASTERSHIP_TRANSFERRED",
			"ROLEPLAY_CAMPAIGN_MEMBER_REMOVED");
	}

	@Test
	void oHistoricoEExclusivoDeAdministrador() throws Exception {
		var master = authHelper().authenticate("eroleplay-auditoria-sec-mestre");
		String campaignId = createCampaign(master, "Trilha Fechada", "PUBLIC", "audit-sec-create");

		// Sem sessão é 401: o resolver lança antes de qualquer leitura.
		mockMvc.perform(get("/admin/roleplay/campaigns/" + campaignId + "/history"))
			.andExpect(status().isUnauthorized());

		// Sessão comum (o próprio mestre da campanha) é 403: ser mestre não é ser admin.
		mockMvc.perform(get("/admin/roleplay/campaigns/" + campaignId + "/history")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isForbidden());

		// Admin com campanha desconhecida é 404 — não uma lista vazia.
		var admin = authHelper().authenticate("eroleplay-auditoria-sec-leitor");
		TestDatabase.setAdmin(admin.userId());
		mockMvc.perform(get("/admin/roleplay/campaigns/roleplay_campaign_inexistente/history")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isNotFound());

		mockMvc.perform(get("/admin/roleplay/campaigns/" + campaignId + "/history")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].action").value("ROLEPLAY_CAMPAIGN_CREATED"))
			.andExpect(jsonPath("$[0].actorId").value(master.userId()));
	}
}
