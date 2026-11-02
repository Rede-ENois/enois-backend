package com.socialcoisa.rede.feed.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

/**
 * Um post publicado numa comunidade precisa carregar, na leitura, o nome da comunidade (para o
 * texto "fulano publicou em Comunidade X") e se quem esta lendo ja participa dela.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommunityOnPostTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	private String createdCommunityId;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@AfterEach
	void cleanUp() {
		if (createdCommunityId != null) {
			TestDatabase.deleteCommunity(createdCommunityId);
		}
	}

	@Test
	void postCarriesCommunityNameAndViewerMembership() throws Exception {
		var author = auth.authenticate("user_comm_author");
		var viewer = auth.authenticate("user_comm_viewer");

		String communityId = createCommunity(author, "Comunidade Java do Vale");
		String postId = publishInCommunity(author, communityId);

		// Autor e membro desde a criacao: joined = true.
		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.community.id").value(communityId))
			.andExpect(jsonPath("$.community.name").value("Comunidade Java do Vale"))
			.andExpect(jsonPath("$.community.joined").value(true));

		// Quem ainda nao segue ve o nome da comunidade, mas joined = false (botao "Seguir" aparece).
		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", viewer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.community.name").value("Comunidade Java do Vale"))
			.andExpect(jsonPath("$.community.joined").value(false));

		mockMvc.perform(post("/communities/" + communityId + "/members")
				.header("X-Session-Token", viewer.token())
				.header("Idempotency-Key", "join-comm-1"))
			.andExpect(status().isOk());

		// Depois de seguir, joined vira true na mesma leitura.
		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", viewer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.community.joined").value(true));
	}

	@Test
	void postOutsideCommunityHasNoCommunity() throws Exception {
		var author = auth.authenticate("user_comm_plain");
		String payload = objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
			"type", "INSIGHT",
			"content", "Post solto no feed, sem comunidade.",
			"tags", List.of(),
			"mediaIds", List.of()));
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "plain-post-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.community").doesNotExist());
	}

	private String createCommunity(TestAuthHelper.AuthSession owner, String name) throws Exception {
		String payload = objectMapper.writeValueAsString(Map.of(
			"name", name,
			"description", "Grupo de quem programa em Java por aqui.",
			"topic", "Tecnologia"));
		MvcResult result = mockMvc.perform(post("/communities")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "create-comm-" + name)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isCreated())
			.andReturn();
		JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
		createdCommunityId = node.get("id").asText();
		return createdCommunityId;
	}

	private String publishInCommunity(TestAuthHelper.AuthSession author, String communityId) throws Exception {
		String payload = objectMapper.writeValueAsString(Map.of(
			"type", "INSIGHT",
			"content", "Primeira publicacao dentro da comunidade.",
			"tags", List.of(),
			"mediaIds", List.of(),
			"communityId", communityId));
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "comm-post-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}