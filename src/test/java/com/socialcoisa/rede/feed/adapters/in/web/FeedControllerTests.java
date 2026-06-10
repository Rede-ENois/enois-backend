package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

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

@SpringBootTest
@AutoConfigureMockMvc
class FeedControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void publishesPostAndReplaysIdempotentResponse() throws Exception {
		var session = auth.authenticate("user_feed_replay");
		String payload = objectMapper.writeValueAsString(Map.of(
			"type", "INSIGHT",
			"content", "Arquitetura hexagonal deixa a regra de negocio respirando melhor.",
			"tags", List.of("Arquitetura", "Java"),
			"mediaIds", List.of()));

		MvcResult first = mockMvc.perform(post("/api/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "feed-replay-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorId").exists())
			.andReturn();

		MvcResult replay = mockMvc.perform(post("/api/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "feed-replay-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isCreated())
			.andReturn();

		assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
	}

	@Test
	void rejectsSameIdempotencyKeyWithDifferentPayload() throws Exception {
		var session = auth.authenticate("user_feed_conflict");
		String first = objectMapper.writeValueAsString(Map.of(
			"type", "INSIGHT",
			"content", "Primeira tentativa",
			"tags", List.of(),
			"mediaIds", List.of()));
		String second = objectMapper.writeValueAsString(Map.of(
			"type", "INSIGHT",
			"content", "Outra tentativa",
			"tags", List.of(),
			"mediaIds", List.of()));

		mockMvc.perform(post("/api/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "feed-conflict-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(first))
			.andExpect(status().isCreated());

		mockMvc.perform(post("/api/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "feed-conflict-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(second))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
	}

	@Test
	void deletesPostAndRemovesItFromFeed() throws Exception {
		var session = auth.authenticate("user_feed_delete");
		String payload = objectMapper.writeValueAsString(Map.of(
			"type", "QUESTION",
			"content", "Como voces organizam portas e adaptadores no monolito?",
			"tags", List.of("Backend"),
			"mediaIds", List.of()));

		MvcResult created = mockMvc.perform(post("/api/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "feed-delete-create-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isCreated())
			.andReturn();

		JsonNode node = objectMapper.readTree(created.getResponse().getContentAsString());
		String postId = node.get("id").asText();

		mockMvc.perform(get("/api/feed")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(postId));

		mockMvc.perform(delete("/api/feed/posts/{postId}", postId)
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "feed-delete-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DELETED"));

		mockMvc.perform(get("/api/feed")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void handlesPostInteractionsWithCountersAndIdempotency() throws Exception {
		var session = auth.authenticate("user_feed_interactions");
		var commenterSession = auth.authenticate("user_feed_commenter");
		var authorReplySession = auth.authenticate("user_feed_author_reply");
		var reactorSession = auth.authenticate("user_feed_reactor");
		var saverSession = auth.authenticate("user_feed_saver");
		var sharerSession = auth.authenticate("user_feed_sharer");
		String payload = objectMapper.writeValueAsString(Map.of(
			"type", "INSIGHT",
			"content", "Interacoes precisam ser pequenas, transacionais e repetiveis.",
			"tags", List.of("Feed"),
			"mediaIds", List.of()));

		MvcResult created = mockMvc.perform(post("/api/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "feed-interactions-create-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isCreated())
			.andReturn();

		String postId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
		String commentPayload = objectMapper.writeValueAsString(Map.of("content", "Concordo com a abordagem."));

		MvcResult comment = mockMvc.perform(post("/api/feed/posts/{postId}/comments", postId)
				.header("X-Session-Token", commenterSession.token())
				.header("Idempotency-Key", "feed-comment-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(commentPayload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.postId").value(postId))
			.andReturn();

		mockMvc.perform(post("/api/feed/posts/{postId}/comments", postId)
				.header("X-Session-Token", commenterSession.token())
				.header("Idempotency-Key", "feed-comment-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(commentPayload))
			.andExpect(status().isCreated())
			.andExpect(result -> assertThat(result.getResponse().getContentAsString())
				.isEqualTo(comment.getResponse().getContentAsString()));

		String commentId = objectMapper.readTree(comment.getResponse().getContentAsString()).get("id").asText();
		String replyPayload = objectMapper.writeValueAsString(Map.of(
			"parentCommentId", commentId,
			"content", "Resposta mantendo a thread no mesmo post."));

		mockMvc.perform(post("/api/feed/posts/{postId}/comments", postId)
				.header("X-Session-Token", authorReplySession.token())
				.header("Idempotency-Key", "feed-reply-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(replyPayload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.parentCommentId").value(commentId));

		mockMvc.perform(post("/api/feed/posts/{postId}/reactions", postId)
				.header("X-Session-Token", reactorSession.token())
				.header("Idempotency-Key", "feed-reaction-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "LIKE"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REACTED"))
			.andExpect(jsonPath("$.counters.reactions").value(1))
			.andExpect(jsonPath("$.counters.comments").value(2));

		mockMvc.perform(post("/api/feed/posts/{postId}/saves", postId)
				.header("X-Session-Token", saverSession.token())
				.header("Idempotency-Key", "feed-save-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SAVED"))
			.andExpect(jsonPath("$.counters.saves").value(1));

		mockMvc.perform(post("/api/feed/posts/{postId}/shares", postId)
				.header("X-Session-Token", sharerSession.token())
				.header("Idempotency-Key", "feed-share-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SHARED"))
			.andExpect(jsonPath("$.counters.shares").value(1));

		mockMvc.perform(get("/api/feed/posts/{postId}/comments", postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2));

		mockMvc.perform(delete("/api/feed/posts/{postId}/reactions", postId)
				.header("X-Session-Token", reactorSession.token())
				.header("Idempotency-Key", "feed-reaction-delete-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REACTION_REMOVED"))
			.andExpect(jsonPath("$.counters.reactions").value(0));

		mockMvc.perform(delete("/api/feed/posts/{postId}/saves", postId)
				.header("X-Session-Token", saverSession.token())
				.header("Idempotency-Key", "feed-save-delete-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SAVE_REMOVED"))
			.andExpect(jsonPath("$.counters.saves").value(0));
	}
}
