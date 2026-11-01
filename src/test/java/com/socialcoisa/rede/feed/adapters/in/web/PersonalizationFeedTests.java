package com.socialcoisa.rede.feed.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * A aplicação das preferências nos feeds (fase C do plano de extensão):
 * um post com assunto bloqueado sai do feed do leitor — mas continua no ar
 * para as outras pessoas e acessível direto. Post sem assunto nunca é bloqueado.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PersonalizationFeedTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;
	/** O H2 dos testes é compartilhado entre classes: comunidade criada aqui é apagada aqui. */
	private final java.util.List<String[]> createdCommunities = new java.util.ArrayList<>();

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@AfterEach
	void cleanUp() throws Exception {
		for (String[] created : createdCommunities) {
			mockMvc.perform(delete("/communities/" + created[0])
					.header("X-Session-Token", created[1])
					.header("Idempotency-Key", "cleanup-" + created[0]));
		}
		createdCommunities.clear();
	}

	private String createCommunity(TestAuthHelper.AuthSession session, String name, String topic)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/communities")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "comm-" + name + "-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"name", name, "description", "Comunidade de teste", "topic", topic))))
			.andExpect(status().isCreated())
			.andReturn();
		String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
		createdCommunities.add(new String[] { id, session.token() });
		return id;
	}

	private String publishInCommunity(TestAuthHelper.AuthSession session, String communityId, String content)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "pcomm-" + content.hashCode() + "-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"type", "INSIGHT", "content", content, "tags", java.util.List.of(),
					"mediaIds", java.util.List.of(), "communityId", communityId))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void blockedTopicLeavesTheForYouFeedAndComesBack() throws Exception {
		var author = auth.authenticate("persfeed_author");
		var reader = auth.authenticate("persfeed_reader");

		// O autor cria uma comunidade de tecnologia, o leitor entra nela.
		String communityId = createCommunity(author, "PersFeed Tech", "TECNOLOGIA");
		mockMvc.perform(post("/communities/" + communityId + "/members")
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "join-persfeed"))
			.andExpect(status().isOk());

		// O autor publica na comunidade: o post herda o assunto TECNOLOGIA.
		String postId = publishInCommunity(author, communityId, "Post de tecnologia da comunidade.");

		// O leitor, sem bloqueio, vê o post no Para você (ele é membro).
		mockMvc.perform(get("/feed").param("tab", "ALL").header("X-Session-Token", reader.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + postId + "')]").isNotEmpty());

		// Bloqueia o assunto: o post some do feed DO LEITOR.
		mockMvc.perform(put("/me/preferences")
				.header("X-Session-Token", reader.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"blockedTopics", java.util.List.of("TECNOLOGIA")))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/feed").param("tab", "ALL").header("X-Session-Token", reader.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + postId + "')]").isEmpty());

		// O AUTOR (sem bloqueio) continua vendo: o filtro é pessoal, não censura.
		mockMvc.perform(get("/feed").param("tab", "ALL").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + postId + "')]").isNotEmpty());

		// Remove o bloqueio: o post volta.
		mockMvc.perform(put("/me/preferences")
				.header("X-Session-Token", reader.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("blockedTopics", java.util.List.of()))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/feed").param("tab", "ALL").header("X-Session-Token", reader.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + postId + "')]").isNotEmpty());
	}

	/**
	 * Liked topic boosts the For You feed even when the page also holds a post without a topic:
	 * the immutable liked list threw on contains(null) (500), and the reversed comparator sent the
	 * liked posts to the bottom.
	 */
	@Test
	void likedTopicComesFirstInForYouAlongsidePostsWithoutTopic() throws Exception {
		var author = auth.authenticate("persfeed_liked");
		String communityId = createCommunity(author, "PersFeed Liked Tech", "TECNOLOGIA");
		String likedPost = publishInCommunity(author, communityId, "Post de tecnologia (mais antigo).");
		MvcResult untopic = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "untopic-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"type", "INSIGHT", "content", "Post sem assunto (mais novo).", "tags", java.util.List.of(),
					"mediaIds", java.util.List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		String untopicPost = objectMapper.readTree(untopic.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(put("/me/preferences")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"likedTopics", java.util.List.of("TECNOLOGIA")))))
			.andExpect(status().isOk());

		MvcResult feed = mockMvc.perform(get("/feed").param("tab", "FOR_YOU").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		java.util.List<String> ids = new java.util.ArrayList<>();
		objectMapper.readTree(feed.getResponse().getContentAsString()).path("items")
			.forEach(item -> ids.add(item.path("id").asText()));
		org.assertj.core.api.Assertions.assertThat(ids).containsSubsequence(likedPost, untopicPost);
	}

	/**
	 * For You pages are date windows and the liked-topics boost only reorders inside a page. Before,
	 * the boost ran before the page was cut: liked posts filled the page, the cursor jumped past the
	 * newer unliked posts and they never showed (and the "voce esta em dia" section had no stable order).
	 */
	@Test
	void forYouPagesAreDateWindowsBoostedInside() throws Exception {
		var author = auth.authenticate("persfeed_pages");
		String communityId = createCommunity(author, "PersFeed Pages Tech", "TECNOLOGIA");
		String liked1 = publishInCommunity(author, communityId, "Pages: liked, oldest.");
		String plain1 = publishPlain(author, "Pages: plain, second.");
		String plain2 = publishPlain(author, "Pages: plain, third.");
		String liked2 = publishInCommunity(author, communityId, "Pages: liked, newest.");

		mockMvc.perform(put("/me/preferences")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"likedTopics", java.util.List.of("TECNOLOGIA")))))
			.andExpect(status().isOk());

		java.util.List<String> ids = new java.util.ArrayList<>();
		String cursor = null;
		do {
			var request = get("/feed").param("tab", "FOR_YOU").param("limit", "2")
				.header("X-Session-Token", author.token());
			if (cursor != null) {
				request.param("cursor", cursor);
			}
			var body = objectMapper.readTree(mockMvc.perform(request).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
			body.path("items").forEach(item -> ids.add(item.path("id").asText()));
			cursor = body.path("nextCursor").isTextual() ? body.path("nextCursor").asText() : null;
		} while (cursor != null);

		// Page 1 = the two newest (liked2, plain2); page 2 = the two oldest, liked first.
		org.assertj.core.api.Assertions.assertThat(ids).containsExactly(liked2, plain2, liked1, plain1);
	}

	private String publishPlain(TestAuthHelper.AuthSession session, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "plain-" + content.hashCode() + "-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"type", "INSIGHT", "content", content, "tags", java.util.List.of(),
					"mediaIds", java.util.List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
