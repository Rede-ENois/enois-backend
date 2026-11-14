package com.socialcoisa.rede.eroleplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * O isolamento do chat contra quem não é da mesa: sem sessão é 401 em todas as rotas; a recusa de
 * participação é 403; a mensagem que não é do leitor não confirma existência (404); a fixação é do
 * mestre; e o registro não tem rota de edição nem de remoção.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampaignChatSecurityTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper authHelper() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	private String createCampaign(TestAuthHelper.AuthSession master, String idemKey) throws Exception {
		MvcResult result = mockMvc.perform(post("/roleplay/campaigns")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "Campanha Chat Seguro " + idemKey,
					"description", "campanha de teste de isolamento do chat",
					"gameSystem", "d20-generico",
					"systemFamily", "D20",
					"visibility", "PUBLIC"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String postMessage(TestAuthHelper.AuthSession author, String campaignId, String idemKey)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("channel", "IC", "content",
					"mensagem de teste de isolamento"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void todasAsRotasExigemSessao() throws Exception {
		var master = authHelper().authenticate("eroleplay-f5-sec-mestre");
		String campaignId = createCampaign(master, "f5-create-sec");
		String messageId = postMessage(master, campaignId, "f5-sec-1");

		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat"))
			.andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat/pinned"))
			.andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat/" + messageId))
			.andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat/presence"))
			.andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat")
				.header("Idempotency-Key", "f5-sec-anon")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("channel", "IC", "content", "x"))))
			.andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat/" + messageId + "/pin")
				.header("Idempotency-Key", "f5-sec-anon-pin"))
			.andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat/" + messageId + "/unpin")
				.header("Idempotency-Key", "f5-sec-anon-unpin"))
			.andReturn().getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void naoParticipaNaoLeNemPublicaNemVepresenca() throws Exception {
		var master = authHelper().authenticate("eroleplay-f5-sec2-mestre");
		var outsider = authHelper().authenticate("eroleplay-f5-sec2-fora");
		String campaignId = createCampaign(master, "f5-create-sec2");

		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat")
				.header("X-Session-Token", outsider.token())).andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat")
				.header("X-Session-Token", outsider.token())
				.header("Idempotency-Key", "f5-sec2-fora-post")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("channel", "IC", "content", "invasao"))))
			.andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat/pinned")
				.header("X-Session-Token", outsider.token())).andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat/presence")
				.header("X-Session-Token", outsider.token())).andReturn().getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void mensagemDeOutroNaoConfirmaExistencia() throws Exception {
		var master = authHelper().authenticate("eroleplay-f5-sec3-mestre");
		var alice = authHelper().authenticate("eroleplay-f5-sec3-alice");
		var bob = authHelper().authenticate("eroleplay-f5-sec3-bob");
		var carol = authHelper().authenticate("eroleplay-f5-sec3-carol");
		String campaignId = createCampaign(master, "f5-create-sec3");
		for (var member : new TestAuthHelper.AuthSession[] { alice, bob, carol }) {
			mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/join")
					.header("X-Session-Token", member.token())
					.header("Idempotency-Key", "f5-sec3-join-" + member.userId()))
				.andExpect(status().isOk());
		}

		MvcResult sent = mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat")
				.header("X-Session-Token", alice.token())
				.header("Idempotency-Key", "f5-sec3-aparte")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("channel", "OOC", "recipientId", bob.userId(),
					"content", "aparte secreto"))))
			.andExpect(status().isCreated())
			.andReturn();
		String messageId = objectMapper.readTree(sent.getResponse().getContentAsString()).get("id").asText();

		// carol participou, mas o aparte não é dela: 404 — não 403, que confirmaria existência.
		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat/" + messageId)
				.header("X-Session-Token", carol.token())).andReturn().getResponse().getStatus()).isEqualTo(404);
		assertThat(mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat/" + messageId + "/pin")
				.header("X-Session-Token", carol.token())
				.header("Idempotency-Key", "f5-sec3-carol-pin"))
			.andReturn().getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void aparteParaNaoParticipanteERecusado() throws Exception {
		var master = authHelper().authenticate("eroleplay-f5-sec4-mestre");
		var alice = authHelper().authenticate("eroleplay-f5-sec4-alice");
		var outsider = authHelper().authenticate("eroleplay-f5-sec4-fora");
		String campaignId = createCampaign(master, "f5-create-sec4");
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/join")
				.header("X-Session-Token", alice.token())
				.header("Idempotency-Key", "f5-sec4-join-alice"))
			.andExpect(status().isOk());

		assertThat(mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat")
				.header("X-Session-Token", alice.token())
				.header("Idempotency-Key", "f5-sec4-destino-fora")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("channel", "OOC", "recipientId", outsider.userId(),
					"content", "aparte pra fora"))))
			.andReturn().getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void idDaMensagemDeOutraCampanhaNaoServe() throws Exception {
		var master = authHelper().authenticate("eroleplay-f5-sec5-mestre");
		String campaignA = createCampaign(master, "f5-create-sec5a");
		String campaignB = createCampaign(master, "f5-create-sec5b");
		String messageInA = postMessage(master, campaignA, "f5-sec5-1");

		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignB + "/chat/" + messageInA)
				.header("X-Session-Token", master.token())).andReturn().getResponse().getStatus()).isEqualTo(404);
		assertThat(mockMvc.perform(post("/roleplay/campaigns/" + campaignB + "/chat/" + messageInA + "/pin")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f5-sec5-pin-cruzado"))
			.andReturn().getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void registroNaoTemRotaDeEdicaoNemRemocao() throws Exception {
		var master = authHelper().authenticate("eroleplay-f5-sec6-mestre");
		String campaignId = createCampaign(master, "f5-create-sec6");
		String messageId = postMessage(master, campaignId, "f5-sec6-1");

		assertThat(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.put("/roleplay/campaigns/" + campaignId + "/chat/" + messageId)
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f5-sec6-put")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "reescrevido"))))
			.andReturn().getResponse().getStatus()).isEqualTo(405);
		assertThat(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/roleplay/campaigns/" + campaignId + "/chat/" + messageId)
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f5-sec6-delete"))
			.andReturn().getResponse().getStatus()).isEqualTo(405);

		MvcResult reread = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat/" + messageId)
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(objectMapper.readTree(reread.getResponse().getContentAsString())
			.get("content").asText()).isEqualTo("mensagem de teste de isolamento");
	}

	@Test
	void canalInvalidoEConteudoVazioSaoRecusadosSemGravar() throws Exception {
		var master = authHelper().authenticate("eroleplay-f5-sec7-mestre");
		String campaignId = createCampaign(master, "f5-create-sec7");

		assertThat(mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f5-sec7-canal")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("channel", "WHISPER", "content", "x"))))
			.andReturn().getResponse().getStatus()).isEqualTo(400);
		assertThat(mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f5-sec7-vazio")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("channel", "IC", "content", "   "))))
			.andReturn().getResponse().getStatus()).isEqualTo(400);

		MvcResult history = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat")
				.header("X-Session-Token", master.token()))
			.andReturn();
		assertThat(body(history).isEmpty()).isTrue();
	}

	private JsonNode body(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}
}
