package com.socialcoisa.rede.feed.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.socialcoisa.rede.feed.domain.PostContentLimits;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * O limite de texto de cada tipo de post.
 *
 * O limite é por tipo porque o tipo é o formato da conversa — dúvida é pergunta curta, tutorial é
 * texto de leitura —, e a regra é a MESMA na criação e na edição (editar não pode ser a porta dos
 * fundos do limite).
 *
 * O teste roda o par de cada tipo: no limite, entra; um caractere acima, é recusado com
 * CONTENT_TOO_LONG. Também confere que a edição de um post de dúvida não aceita o texto que caberia
 * num tutorial — é o caso que passaria batido se o limite só existisse na criação.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostContentLimitsTests {

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
	void eachTypeAcceptsItsLimitAndRefusesOneMore() throws Exception {
		var author = auth.authenticate("plimits_author1");

		for (PostType type : List.of(PostType.QUESTION, PostType.REVIEW, PostType.PROJECT, PostType.NEWS,
				PostType.OPPORTUNITY, PostType.TUTORIAL, PostType.INSIGHT)) {
			int limit = PostContentLimits.of(type);

			publish(author, type, "x".repeat(limit), "plimits-" + type + "-ok").andExpect(status().isCreated());
			publish(author, type, "x".repeat(limit + 1), "plimits-" + type + "-long")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("CONTENT_TOO_LONG"));
		}
	}

	/**
	 * Editar uma duvida com o texto de um tutorial e recusado como na criacao. The edit used to measure
	 * the text after defuse (which shortens repeated runs), so a long run slipped through.
	 */
	@Test
	void editingKeepsTheLimitOfTheType() throws Exception {
		var author = auth.authenticate("plimits_author2");

		MvcResult created = publish(author, PostType.QUESTION, "Uma duvida curta", "plimits-edit").andReturn();
		String postId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		// O texto que caberia num tutorial não cabe numa dúvida — nem na edição.
		mockMvc.perform(patch("/feed/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "plimits-edit-long")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "x".repeat(PostContentLimits.of(PostType.TUTORIAL))))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CONTENT_TOO_LONG"));

		// E o que cabe continua entrando.
		mockMvc.perform(patch("/feed/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "plimits-edit-ok")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "x".repeat(PostContentLimits.of(PostType.QUESTION))))))
			.andExpect(status().isOk());
	}

	private org.springframework.test.web.servlet.ResultActions publish(TestAuthHelper.AuthSession author, PostType type,
			String content, String key) throws Exception {
		return mockMvc.perform(post("/feed/posts")
			.header("X-Session-Token", author.token())
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", type.name(), "content", content, "tags", List.of(),
				"mediaIds", List.of()))));
	}
}
