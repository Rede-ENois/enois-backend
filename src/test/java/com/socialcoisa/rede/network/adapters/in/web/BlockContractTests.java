package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * Bloqueio tem que fazer o usuario sumir de verdade: feed, comentarios, notificacoes e busca —
 * e sem volta pela porta da conexao.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BlockContractTests {

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
	void blockedAuthorDisappearsFromTheFeedUntilUnblocked() throws Exception {
		var author = auth.authenticate("blk_author");
		var reader = auth.authenticate("blk_reader");

		String postId = publishPost(author, "Conteudo de quem sera bloqueado");

		assertThat(feedContainsPost(reader, postId)).isTrue();

		mockMvc.perform(post("/network/blocks/" + author.userId())
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "block-1"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("BLOCKED"));

		assertThat(feedContainsPost(reader, postId)).isFalse();

		// Link direto tambem deixa de resolver.
		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", reader.token()))
			.andExpect(status().isNotFound());

		mockMvc.perform(get("/network/blocks").header("X-Session-Token", reader.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].userId").value(author.userId()));

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/network/blocks/" + author.userId())
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "unblock-1"))
			.andExpect(status().isOk());

		assertThat(feedContainsPost(reader, postId)).isTrue();
	}

	@Test
	void blockedAuthorDisappearsFromComments() throws Exception {
		var author = auth.authenticate("blk_comment_author");
		var reader = auth.authenticate("blk_comment_reader");

		String postId = publishPost(reader, "Post com comentario de quem sera bloqueado");
		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "blk-comment-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "Comentario do bloqueado"))))
			.andExpect(status().isCreated());

		assertThat(commentsBody(reader, postId)).contains("Comentario do bloqueado");

		mockMvc.perform(post("/network/blocks/" + author.userId())
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "block-comments-1"))
			.andExpect(status().isCreated());

		assertThat(commentsBody(reader, postId)).doesNotContain("Comentario do bloqueado");
	}

	@Test
	void blockedUserCannotRequestAConnectionAgain() throws Exception {
		var blocked = auth.authenticate("blk_requester");
		var blocker = auth.authenticate("blk_blocker");

		mockMvc.perform(post("/network/blocks/" + blocked.userId())
				.header("X-Session-Token", blocker.token())
				.header("Idempotency-Key", "block-conn-1"))
			.andExpect(status().isCreated());

		mockMvc.perform(post("/network/connections/" + blocker.userId())
				.header("X-Session-Token", blocked.token())
				.header("Idempotency-Key", "block-conn-2"))
			.andExpect(status().isForbidden());
	}

	@Test
	void cannotBlockYourself() throws Exception {
		var session = auth.authenticate("blk_self");

		mockMvc.perform(post("/network/blocks/" + session.userId())
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "block-self-1"))
			.andExpect(status().isBadRequest());
	}

	private String publishPost(TestAuthHelper.AuthSession session, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "post-" + session.userId() + "-" + content.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT",
					"content", content,
					"tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	/** A aba ALL ve os posts de todo mundo, entao serve para observar um autor especifico. */
	private boolean feedContainsPost(TestAuthHelper.AuthSession session, String postId) throws Exception {
		MvcResult result = mockMvc.perform(get("/feed")
				.param("tab", "ALL")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return result.getResponse().getContentAsString().contains(postId);
	}

	private String commentsBody(TestAuthHelper.AuthSession session, String postId) throws Exception {
		return mockMvc.perform(get("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}
}