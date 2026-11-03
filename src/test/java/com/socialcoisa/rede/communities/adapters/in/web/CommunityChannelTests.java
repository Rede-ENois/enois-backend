package com.socialcoisa.rede.communities.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
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
 * Canais privados da comunidade: só membros veem e publicam; o post de canal não aparece em
 * feed nenhum (comunidade, principal ou perfil).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommunityChannelTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private String createCommunity(TestAuthHelper.AuthSession owner, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/communities")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "chan-community-" + name)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", name, "description", "Comunidade de teste.", "topic", "TECNOLOGIA"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String createChannel(TestAuthHelper.AuthSession member, String communityId, String name, String topic)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/communities/" + communityId + "/channels")
				.header("X-Session-Token", member.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "topic", topic))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String publishInChannel(TestAuthHelper.AuthSession member, String communityId, String channelId,
			String content, String key) throws Exception {
		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", member.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "INSIGHT",
					"content", content,
					"communityId", communityId,
					"channelId", channelId))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void channelsAreVisibleOnlyToCommunityMembersAndStayOutOfEveryFeed() throws Exception {
		var member = auth.authenticate("chan_member");
		var outsider = auth.authenticate("chan_outsider");
		String communityId = createCommunity(member, "Comunidade com canais");
		String channelId = createChannel(member, communityId, "Bastidores", "CARREIRA");

		// Um segundo canal, outro tema.
		mockMvc.perform(post("/communities/" + communityId + "/channels")
				.header("X-Session-Token", member.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Carreira", "topic", "LIDERANCA"))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/communities/" + communityId + "/channels")
				.header("X-Session-Token", member.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2));

		// Não membro: nem lista, nem cria, nem lê.
		mockMvc.perform(get("/communities/" + communityId + "/channels")
				.header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_A_MEMBER"));

		mockMvc.perform(post("/communities/" + communityId + "/channels")
				.header("X-Session-Token", outsider.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Invasor"))))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/communities/" + communityId + "/channels/" + channelId + "/posts")
				.header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden());

		// Visitante nem entra (401), nunca uma lista vazia enganosa.
		mockMvc.perform(get("/communities/" + communityId + "/channels"))
			.andExpect(status().isUnauthorized());

		// Membro publica no canal.
		String content = "Segredo do canal: o bastidor fica aqui.";
		String postId = publishInChannel(member, communityId, channelId, content, "chan-post-1");

		// O link direto do post respeita o canal: sem sessao ou de fora, e 404 (nao confirma que
		// o post existe); so o membro le o conteudo.
		mockMvc.perform(get("/feed/posts/" + postId))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/feed/posts/" + postId)
				.header("X-Session-Token", outsider.token()))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", outsider.token()))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/feed/posts/" + postId)
				.header("X-Session-Token", member.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value(content));

		// O feed do canal mostra; o feed da comunidade não mostra.
		mockMvc.perform(get("/communities/" + communityId + "/channels/" + channelId + "/posts")
				.header("X-Session-Token", member.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].content").value(content));

		mockMvc.perform(get("/communities/" + communityId + "/feed")
				.header("X-Session-Token", member.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0));

		// Feed principal e perfil do autor: nada de canal.
		String all = mockMvc.perform(get("/feed").param("tab", "ALL")
				.header("X-Session-Token", member.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(all).doesNotContain(content);

		String profilePosts = mockMvc.perform(get("/users/chan_member/posts")
				.header("X-Session-Token", member.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(profilePosts).doesNotContain(content);
	}

	@Test
	void channelRulesAreEnforcedByTheBackend() throws Exception {
		var member = auth.authenticate("chan_rules");
		String communityId = createCommunity(member, "Comunidade regras");
		String channelId = createChannel(member, communityId, "Geral", "PRODUTO");

		// Canal sem comunidade.
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", member.token())
				.header("Idempotency-Key", "chan-no-community")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Sem comunidade.", "channelId", channelId))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CHANNEL_REQUIRES_COMMUNITY"));

		// Canal define o tema: mandar assunto junto é recusado.
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", member.token())
				.header("Idempotency-Key", "chan-with-topic")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "INSIGHT", "content", "Com assunto.", "communityId", communityId,
					"channelId", channelId, "topic", "TECNOLOGIA"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TOPIC_FROM_CHANNEL"));

		// Canal de outra comunidade: 404.
		String otherCommunity = createCommunity(member, "Outra comunidade");
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", member.token())
				.header("Idempotency-Key", "chan-wrong-community")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "INSIGHT", "content", "Canal errado.", "communityId", otherCommunity,
					"channelId", channelId))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
	}
}
