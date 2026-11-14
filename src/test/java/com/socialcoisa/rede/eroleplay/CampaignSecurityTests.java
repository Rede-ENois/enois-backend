package com.socialcoisa.rede.eroleplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import tools.jackson.databind.ObjectMapper;

/**
 * O isolamento da campanha no backend.
 *
 * <p>O que este teste tranca:
 *
 * - toda escrita exige sessão (o interceptor global já cobre, mas a camada do produto também
 *   não pode assumir nada);
 * - quem não participa não edita a campanha, não convida, não decide pedidos e não senta;
 * - um jogador não edita nem troca o comando;
 * - o conteúdo de uma campanha não é alcançado por quem não participa, mesmo com o id na mão;
 * - a listagem de participantes exige participação (o mestre enxerga todos; o jogador também
 *   enxerga a mesa, mas a ficha de cada um continua sendo dele — a fase de fichas chega depois).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampaignSecurityTests {

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

	@Test
	void escritaSemSessaoNaoPassa() throws Exception {
		var master = authHelper().authenticate("eroleplay-sec-master");
		String campaignId = createCampaign(master, "Sem Sessao", "create-sem-sessao");

		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/join"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/roleplay/campaigns/" + campaignId + "/members/me"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(patch("/roleplay/campaigns/" + campaignId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Invadido"))))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void quemNaoParticipaNaoAdministraACampanha() throws Exception {
		var master = authHelper().authenticate("eroleplay-sec-mestre");
		String campaignId = createCampaign(master, "Campanha do Mestre", "create-campanha-mestre");

		var outsider = authHelper().authenticate("eroleplay-sec-fora");
		MvcResult edit = mockMvc.perform(patch("/roleplay/campaigns/" + campaignId)
				.header("X-Session-Token", outsider.token())
				.header("Idempotency-Key", "patch-outsider")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Invadido"))))
			.andReturn();
		assertThat(edit.getResponse().getStatus()).isEqualTo(403);
		assertThat(edit.getResponse().getContentAsString()).contains("FORBIDDEN");

		MvcResult invite = mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/invite")
				.header("X-Session-Token", outsider.token())
				.header("Idempotency-Key", "invite-outsider")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", "qualquer"))))
			.andReturn();
		assertThat(invite.getResponse().getStatus()).isEqualTo(403);

		MvcResult requests = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/requests")
				.header("X-Session-Token", outsider.token()))
			.andReturn();
		assertThat(requests.getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void oJogadorNaoEditaNemTransfereOComando() throws Exception {
		var master = authHelper().authenticate("eroleplay-sec-mestre-jogador");
		String campaignId = createCampaign(master, "Com Roles", "create-com-roles");

		var player = authHelper().authenticate("eroleplay-sec-jogador");
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/join")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "join-sec-jogador"))
			.andExpect(status().isOk());

		MvcResult edit = mockMvc.perform(patch("/roleplay/campaigns/" + campaignId)
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "patch-jogador")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Invadido"))))
			.andReturn();
		assertThat(edit.getResponse().getStatus()).isEqualTo(403);

		MvcResult transfer = mockMvc.perform(
				post("/roleplay/campaigns/" + campaignId + "/members/" + master.userId() + "/transfer-master")
					.header("X-Session-Token", player.token())
					.header("Idempotency-Key", "transfer-jogador"))
			.andReturn();
		assertThat(transfer.getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void oJogadorNaoRemoveNemRemoveOParticipante() throws Exception {
		var master = authHelper().authenticate("eroleplay-sec-mestre-remocao");
		String campaignId = createCampaign(master, "Remocao", "create-remocao");

		var player = authHelper().authenticate("eroleplay-sec-jogador-remocao");
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/join")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "join-remocao"))
			.andExpect(status().isOk());

		MvcResult removal = mockMvc.perform(
				delete("/roleplay/campaigns/" + campaignId + "/members/" + player.userId())
					.header("X-Session-Token", player.token())
					.header("Idempotency-Key", "remove-jogador"))
			.andReturn();
		assertThat(removal.getResponse().getStatus()).isEqualTo(403);
	}
}
