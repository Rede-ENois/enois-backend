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

/** Desfazer a exclusao do proprio post (exclusao logica reversivel). */
@SpringBootTest
@AutoConfigureMockMvc
class RestorePostTests {

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
	void ownerRestoresDeletedPost() throws Exception {
		var author = auth.authenticate("restore_owner");
		String postId = publish(author);

		mockMvc.perform(delete("/feed/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "del-1"))
			.andExpect(status().isOk());

		// Excluido: leitura publica nao encontra.
		mockMvc.perform(get("/feed/posts/" + postId)).andExpect(status().isNotFound());

		mockMvc.perform(post("/feed/posts/" + postId + "/restore")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "restore-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("Post que sera excluido e restaurado."));

		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", author.token()))
			.andExpect(status().isOk());
	}

	@Test
	void restoringAnActivePostIsRejected() throws Exception {
		var author = auth.authenticate("restore_active");
		String postId = publish(author);

		mockMvc.perform(post("/feed/posts/" + postId + "/restore")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "restore-active-1"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("POST_NOT_DELETED"));
	}

	@Test
	void anotherUserCannotRestore() throws Exception {
		var author = auth.authenticate("restore_owner2");
		var intruder = auth.authenticate("restore_intruder");
		String postId = publish(author);
		mockMvc.perform(delete("/feed/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "del-2"))
			.andExpect(status().isOk());

		mockMvc.perform(post("/feed/posts/" + postId + "/restore")
				.header("X-Session-Token", intruder.token())
				.header("Idempotency-Key", "restore-intrude-1"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("POST_OWNER_MISMATCH"));
	}

	private String publish(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "pub-restore-" + session.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Post que sera excluido e restaurado.",
					"tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
