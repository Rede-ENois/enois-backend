package com.socialcoisa.rede.feed.adapters.in.web;

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

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/** Comentário fixado: um por post, no topo, com permissão do autor do post ou do comentário. */
@SpringBootTest
@AutoConfigureMockMvc
class PinnedCommentTests {

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
	void pinningMovesOneCommentToTheTopAndUnpinsThePrevious() throws Exception {
		var author = auth.authenticate("pin_author_" + System.nanoTime());
		var commenter = auth.authenticate("pin_commenter_" + System.nanoTime());
		var intruder = auth.authenticate("pin_intruder_" + System.nanoTime());
		String postId = publish(author);
		String first = comment(commenter, postId, "Primeiro comentário da lista.");
		String second = comment(commenter, postId, "Segundo comentário da lista.");

		// O autor do POST fixa qualquer comentário.
		mockMvc.perform(post("/feed/posts/" + postId + "/comments/" + first + "/pin")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "pin-1"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/feed/posts/" + postId + "/comments").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(first))
			.andExpect(jsonPath("$.items[0].pinned").value(true));

		// O autor do COMENTÁRIO pode trocar o fixado: o anterior desafixa sozinho.
		mockMvc.perform(post("/feed/posts/" + postId + "/comments/" + second + "/pin")
				.header("X-Session-Token", commenter.token())
				.header("Idempotency-Key", "pin-2"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/feed/posts/" + postId + "/comments").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(second))
			.andExpect(jsonPath("$.items[0].pinned").value(true))
			.andExpect(jsonPath("$.items[?(@.id == '" + first + "')].pinned",
				org.hamcrest.Matchers.hasItem(false)));

		// Terceiros não fixam.
		mockMvc.perform(post("/feed/posts/" + postId + "/comments/" + first + "/pin")
				.header("X-Session-Token", intruder.token())
				.header("Idempotency-Key", "pin-3"))
			.andExpect(status().isForbidden());

		// Desafixar devolve o comentário à ordem normal.
		mockMvc.perform(delete("/feed/posts/" + postId + "/comments/" + second + "/pin")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "pin-4"))
			.andExpect(status().isOk());
		mockMvc.perform(get("/feed/posts/" + postId + "/comments").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.pinned == true)]").isEmpty());
	}

	@Test
	void repliesCannotBePinned() throws Exception {
		var author = auth.authenticate("pin_reply_author_" + System.nanoTime());
		var commenter = auth.authenticate("pin_reply_user_" + System.nanoTime());
		String postId = publish(author);
		String root = comment(commenter, postId, "Comentário raiz.");
		String reply = reply(commenter, postId, root, "Uma resposta dentro da thread.");

		mockMvc.perform(post("/feed/posts/" + postId + "/comments/" + reply + "/pin")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "pin-reply"))
			.andExpect(status().isNotFound());
	}

	private String publish(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "pin-post-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Post para testar comentário fixado.",
					"tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}

	private String comment(TestAuthHelper.AuthSession session, String postId, String content) throws Exception {
		return comment(session, postId, content, null);
	}

	private String reply(TestAuthHelper.AuthSession session, String postId, String parentId, String content)
			throws Exception {
		return comment(session, postId, content, parentId);
	}

	private String comment(TestAuthHelper.AuthSession session, String postId, String content, String parentId)
			throws Exception {
		java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("content", content);
		payload.put("parentCommentId", parentId);
		payload.put("mediaIds", List.of());
		payload.put("mentionedUserIds", List.of());
		payload.put("website", "");
		MvcResult result = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "pin-comment-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(payload)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}
}
