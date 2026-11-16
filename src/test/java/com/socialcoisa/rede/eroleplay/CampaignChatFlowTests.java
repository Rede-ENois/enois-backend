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
 * O chat de campanha, de ponta a ponta: publicar, ler com o recorte de participação, aparte
 * privado visível só para as pontas e o mestre, fixação do mestre e o mesmo Idempotency-Key não
 * publica duas vezes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampaignChatFlowTests {

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
					"description", "campanha de teste de chat",
					"gameSystem", "d20-generico",
					"systemFamily", "D20",
					"visibility", "PUBLIC"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void join(TestAuthHelper.AuthSession user, String campaignId) throws Exception {
		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/join")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "f5-join-" + campaignId + "-" + user.userId()))
			.andExpect(status().isOk());
	}

	private MvcResult publish(TestAuthHelper.AuthSession author, String campaignId, String idemKey, String channel,
			String content) throws Exception {
		return mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("channel", channel, "content", content))))
			.andReturn();
	}

	private MvcResult publishPrivate(TestAuthHelper.AuthSession author, String campaignId, String idemKey,
			String recipientId, String content) throws Exception {
		return mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("channel", "OOC", "recipientId", recipientId,
					"content", content))))
			.andReturn();
	}

	private JsonNode body(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	@Test
	void publicarEGravarNoCanal() throws Exception {
		var master = authHelper().authenticate("eroleplay-f5-post-mestre");
		String campaignId = createCampaign(master, "Chat Posta", "f5-create-posta");

		MvcResult posted = publish(master, campaignId, "f5-post-1", "IC", "o elfo abre a porta");
		assertThat(posted.getResponse().getStatus()).isEqualTo(201);
		JsonNode message = body(posted);
		assertThat(message.get("channel").asText()).isEqualTo("IC");
		assertThat(message.get("authorId").asText()).isEqualTo(master.userId());
		assertThat(message.get("content").asText()).isEqualTo("o elfo abre a porta");
		assertThat(message.get("pinned").asBoolean()).isFalse();
		assertThat(message.get("id").asText()).isNotBlank();

		MvcResult history = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat")
				.param("channel", "IC")
				.header("X-Session-Token", master.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode list = body(history);
		assertThat(list.isArray()).isTrue();
		assertThat(list.get(0).get("id").asText()).isEqualTo(message.get("id").asText());
	}

	@Test
	void mesmoIdempotencyKeyNaoPublicaDuasVezes() throws Exception {
		var master = authHelper().authenticate("eroleplay-f5-idem-mestre");
		String campaignId = createCampaign(master, "Chat Idem", "f5-create-idem");

		publish(master, campaignId, "f5-idem-mesma", "OOC", "pausa pra jantar");
		publish(master, campaignId, "f5-idem-mesma", "OOC", "pausa pra jantar");

		MvcResult history = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat")
				.param("channel", "OOC")
				.header("X-Session-Token", master.token()))
			.andReturn();
		int ocorrencias = 0;
		for (JsonNode message : body(history)) {
			if (message.get("content").asText().equals("pausa pra jantar")) {
				ocorrencias++;
			}
		}
		assertThat(ocorrencias).isEqualTo(1);
	}

	@Test
	void canaisSaoSeparados() throws Exception {
		var master = authHelper().authenticate("eroleplay-f5-canal-mestre");
		String campaignId = createCampaign(master, "Chat Canais", "f5-create-canal");

		publish(master, campaignId, "f5-canal-ic", "IC", "fala o personagem");
		publish(master, campaignId, "f5-canal-ooc", "OOC", "fala a pessoa");

		MvcResult ic = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat")
				.param("channel", "IC")
				.header("X-Session-Token", master.token()))
			.andReturn();
		assertThat(body(ic).get(0).get("content").asText()).isEqualTo("fala o personagem");

		MvcResult ooc = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat")
				.param("channel", "OOC")
				.header("X-Session-Token", master.token()))
			.andReturn();
		assertThat(body(ooc).get(0).get("content").asText()).isEqualTo("fala a pessoa");
	}

	@Test
	void participanteLeELeNaoParticipaNaoLe() throws Exception {
		var master = authHelper().authenticate("eroleplay-f5-mem-mestre");
		var player = authHelper().authenticate("eroleplay-f5-mem-jogador");
		var outsider = authHelper().authenticate("eroleplay-f5-mem-fora");
		String campaignId = createCampaign(master, "Chat Membros", "f5-create-mem");
		join(player, campaignId);
		publish(master, campaignId, "f5-mem-1", "IC", "cena inicial");

		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat")
				.header("X-Session-Token", player.token())).andReturn().getResponse().getStatus()).isEqualTo(200);

		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat")
				.header("X-Session-Token", outsider.token())).andReturn().getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void apartePrivadoSoAsPontasEOMestre() throws Exception {
		var master = authHelper().authenticate("eroleplay-f5-priv-mestre");
		var alice = authHelper().authenticate("eroleplay-f5-priv-alice");
		var bob = authHelper().authenticate("eroleplay-f5-priv-bob");
		var carol = authHelper().authenticate("eroleplay-f5-priv-carol");
		String campaignId = createCampaign(master, "Chat Aparte", "f5-create-priv");
		join(alice, campaignId);
		join(bob, campaignId);
		join(carol, campaignId);

		MvcResult sent = publishPrivate(alice, campaignId, "f5-priv-1", bob.userId(),
			"meu personagem sabe da armadilha");
		assertThat(sent.getResponse().getStatus()).isEqualTo(201);
		String messageId = body(sent).get("id").asText();

		// remetente lê; destinatário lê; mestre lê; terceiro NÃO vê na listagem e leva 404 por id.
		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat/" + messageId)
				.header("X-Session-Token", bob.token())).andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat/" + messageId)
				.header("X-Session-Token", master.token())).andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat/" + messageId)
				.header("X-Session-Token", carol.token())).andReturn().getResponse().getStatus()).isEqualTo(404);

		MvcResult oocDeCarol = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat")
				.param("channel", "OOC")
				.header("X-Session-Token", carol.token()))
			.andReturn();
		for (JsonNode message : body(oocDeCarol)) {
			assertThat(message.get("id").asText()).isNotEqualTo(messageId);
		}
		// para o mestre o aparte aparece (ele é da mesa).
		MvcResult oocDoMestre = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat")
				.param("channel", "OOC")
				.header("X-Session-Token", master.token()))
			.andReturn();
		assertThat(body(oocDoMestre).get(0).get("id").asText()).isEqualTo(messageId);
	}

	@Test
	void mestreFixaEJogadorNao() throws Exception {
		var master = authHelper().authenticate("eroleplay-f5-pin-mestre");
		var player = authHelper().authenticate("eroleplay-f5-pin-jogador");
		String campaignId = createCampaign(master, "Chat Pin", "f5-create-pin");
		join(player, campaignId);
		MvcResult sent = publish(master, campaignId, "f5-pin-1", "OOC", "sessão todo sábado 20h");
		String messageId = body(sent).get("id").asText();

		assertThat(mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat/" + messageId + "/pin")
				.header("X-Session-Token", player.token())
				.header("Idempotency-Key", "f5-pin-jogador"))
			.andReturn().getResponse().getStatus()).isEqualTo(403);

		assertThat(mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat/" + messageId + "/pin")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f5-pin-mestre"))
			.andReturn().getResponse().getStatus()).isEqualTo(200);

		// o fixado aparece na lista de fixados, com o conteúdo preservado.
		MvcResult pinned = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat/pinned")
				.header("X-Session-Token", player.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(body(pinned).get(0).get("id").asText()).isEqualTo(messageId);
		assertThat(body(pinned).get(0).get("content").asText()).isEqualTo("sessão todo sábado 20h");
		assertThat(body(pinned).get(0).get("pinned").asBoolean()).isTrue();

		// desfixar volta ao estado aberto e a mensagem sai do fixado.
		assertThat(mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat/" + messageId + "/unpin")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f5-unpin-mestre"))
			.andReturn().getResponse().getStatus()).isEqualTo(200);
		MvcResult pinnedDepois = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat/pinned")
				.header("X-Session-Token", master.token()))
			.andReturn();
		assertThat(body(pinnedDepois).isEmpty()).isTrue();
	}

	@Test
	void fixacaoPreservaOConteudo() throws Exception {
		var master = authHelper().authenticate("eroleplay-f5-pincont-mestre");
		String campaignId = createCampaign(master, "Chat Pin Conteudo", "f5-create-pincont");
		String conteudo = "a porta do norte só abre com as duas chaves";
		MvcResult sent = publish(master, campaignId, "f5-pincont-1", "IC", conteudo);
		String messageId = body(sent).get("id").asText();

		mockMvc.perform(post("/roleplay/campaigns/" + campaignId + "/chat/" + messageId + "/pin")
				.header("X-Session-Token", master.token())
				.header("Idempotency-Key", "f5-pincont-pin"))
			.andExpect(status().isOk());

		MvcResult reread = mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat/" + messageId)
				.header("X-Session-Token", master.token()))
			.andReturn();
		assertThat(body(reread).get("content").asText()).isEqualTo(conteudo);
		assertThat(body(reread).get("authorId").asText()).isEqualTo(master.userId());
		assertThat(body(reread).get("channel").asText()).isEqualTo("IC");
	}

	@Test
	void presencaSoParaParticipante() throws Exception {
		var master = authHelper().authenticate("eroleplay-f5-pres-mestre");
		var outsider = authHelper().authenticate("eroleplay-f5-pres-fora");
		String campaignId = createCampaign(master, "Chat Presenca", "f5-create-pres");

		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat/presence")
				.header("X-Session-Token", master.token())).andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(mockMvc.perform(get("/roleplay/campaigns/" + campaignId + "/chat/presence")
				.header("X-Session-Token", outsider.token())).andReturn().getResponse().getStatus()).isEqualTo(403);
	}
}
