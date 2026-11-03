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

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/** A reacao do comentario precisa voltar na listagem (senao "some" ao recarregar). */
@SpringBootTest
@AutoConfigureMockMvc
class CommentReactionStateTests {

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
	void commentReactionIsReturnedOnReload() throws Exception {
		var author = auth.authenticate("creact_author");
		var reactor = auth.authenticate("creact_reactor");
		String postId = publish(author);
		String commentId = comment(reactor, postId);

		mockMvc.perform(post("/feed/posts/" + postId + "/comments/" + commentId + "/reactions")
				.header("X-Session-Token", reactor.token())
				.header("Idempotency-Key", "creact-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "LIKE"))))
			.andExpect(status().isOk());

		// Recarregar a listagem (como o front faz) precisa trazer a reacao do leitor.
		mockMvc.perform(get("/feed/posts/" + postId + "/comments").header("X-Session-Token", reactor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].currentUserReaction").value("LIKE"))
			// As pilulas do comentario (igual ao post) precisam da contagem por tipo no reload.
			.andExpect(jsonPath("$.items[0].reactionSummary.LIKE").value(1))
			.andExpect(jsonPath("$.items[0].reactionCount").value(1));

		// Para outro usuario, nao ha reacao propria.
		mockMvc.perform(get("/feed/posts/" + postId + "/comments").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].currentUserReaction").doesNotExist());
	}

	@Test
	void newCommentReactionTypesWork() throws Exception {
		// O set de reacoes de comentario cresceu (olhos, anotacao, fogo, palmas) — o backend
		// aceita todos, e a reacao volta no reload como as antigas.
		var author = auth.authenticate("creact_author2");
		var reactor = auth.authenticate("creact_reactor2");
		String postId = publish(author);
		String commentId = comment(reactor, postId);

		for (String type : List.of("EYES", "NOTE", "FIRE", "CLAP")) {
			String idem = "creact2-" + type;
			mockMvc.perform(post("/feed/posts/" + postId + "/comments/" + commentId + "/reactions")
					.header("X-Session-Token", reactor.token())
					.header("Idempotency-Key", idem)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("type", type))))
				.andExpect(status().isOk());

			mockMvc.perform(get("/feed/posts/" + postId + "/comments").header("X-Session-Token", reactor.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.id == '" + commentId + "')].currentUserReaction").value(type));

			// Troca (o proximo tipo substitui) e o ciclo recomeca.
		}

		// Remove: volta ao estado sem reacao.
		mockMvc.perform(post("/feed/posts/" + postId + "/comments/" + commentId + "/reactions")
				.header("X-Session-Token", reactor.token())
				.header("Idempotency-Key", "creact2-remove")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "EYES"))))
			.andDo(r -> { /* troca para EYES (mesma da ultima), so para garantir estado */ });

		mockMvc.perform(delete("/feed/posts/" + postId + "/comments/" + commentId + "/reactions")
				.header("X-Session-Token", reactor.token())
				.header("Idempotency-Key", "creact2-del"))
			.andExpect(status().isOk());
	}

	private String publish(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "creact-pub-" + session.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Post para reacao de comentario.",
					"tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String comment(TestAuthHelper.AuthSession session, String postId) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "creact-c-" + session.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "Comentario com reacao."))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
