package com.socialcoisa.rede.feed.adapters.in.web;

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
 * A aba "Ainda nao vi": o oposto de "Discussoes" (que sao os posts em que a pessoa comentou).
 * Aqui entram os posts em que a pessoa ainda nao REAGIU nem COMENTOU.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnseenFeedTabTests {

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
	void unseenShowsThePostsThePersonDidNotReactToNorComment() throws Exception {
		var leitor = auth.authenticate("unseen_leitor");
		var autor = auth.authenticate("unseen_autor");

		String reagir = publicar(autor, "Post em que o leitor reage.");
		String comentar = publicar(autor, "Post em que o leitor comenta.");
		String intacto = publicar(autor, "Post em que o leitor nao encostou.");

		mockMvc.perform(post("/feed/posts/" + reagir + "/reactions")
				.header("X-Session-Token", leitor.token())
				.header("Idempotency-Key", "unseen-react-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "LIKE"))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/feed/posts/" + comentar + "/comments")
				.header("X-Session-Token", leitor.token())
				.header("Idempotency-Key", "unseen-comment-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "Comentario do leitor."))))
			.andExpect(status().isCreated());

		// A aba: o post intocado entra, os outros dois nao.
		mockMvc.perform(get("/feed").param("tab", "UNSEEN").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + intacto + "')]").isNotEmpty())
			.andExpect(jsonPath("$.items[?(@.id == '" + reagir + "')]").isEmpty())
			.andExpect(jsonPath("$.items[?(@.id == '" + comentar + "')]").isEmpty());
	}

	@Test
	void unseenDoesNotShowThePersonsOwnPosts() throws Exception {
		var leitor = auth.authenticate("unseen_dono");
		String proprio = publicar(leitor, "Post do proprio leitor.");

		mockMvc.perform(get("/feed").param("tab", "UNSEEN").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + proprio + "')]").isEmpty());
	}

	private String publicar(TestAuthHelper.AuthSession autor, String conteudo) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "unseen-post-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", conteudo, "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
