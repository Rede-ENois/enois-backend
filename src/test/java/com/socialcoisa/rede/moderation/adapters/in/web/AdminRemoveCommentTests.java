package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Remoção administrativa de COMENTÁRIO: motivo obrigatório, sem denúncia e sem votação, e a rota
 * fecha para todo mundo que não é administrador da plataforma (inclusive moderador aprovado).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminRemoveCommentTests {

	private static final String REASON = "Comentário com ofensa direta a outra pessoa, fora das regras.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@DynamicPropertySource
	static void adminAllowlist(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "acomment_admin@test.com");
	}

	@Test
	void adminRemoveComentarioEAAuditoriaGuardaOMotivo() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var autor = auth.authenticate("acomment_autor");
		var admin = auth.authenticate("acomment_admin");
		TestDatabase.setAdmin(admin.userId());

		String postId = publish(autor, "Post que recebe o comentário.", "acomment-post-1");
		MvcResult comment = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "acomment-comment-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "Comentário que será removido."))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
		String commentId = objectMapper.readTree(comment.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(post("/admin/moderation/comments/" + commentId + "/remove")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "acomment-remove-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REMOVED"));

		// Soft delete: a linha fica (histórico/recurso), mas o comentário sai de toda lista.
		assertThat(one("select status from comments where id = ?", commentId)).isNotEqualTo("ACTIVE");
		mockMvc.perform(get("/feed/posts/" + postId + "/comments").header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + commentId + "')]").isEmpty());
		assertThat(one("select metadata from audit_logs where action = 'ADMIN_COMMENT_REMOVED' "
			+ "and target_id = ?", commentId)).contains("fora das regras");

		// Repetir não inventa sucesso: o comentário já não está no ar.
		mockMvc.perform(post("/admin/moderation/comments/" + commentId + "/remove")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "acomment-remove-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ALREADY_REMOVED"));

		// Id inexistente é 404, e sem motivo nada é removido.
		mockMvc.perform(post("/admin/moderation/comments/cmt_nao_existe/remove")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "acomment-remove-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isNotFound());
		MvcResult outro = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "acomment-comment-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "Comentário que continua no ar."))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
		String outroId = objectMapper.readTree(outro.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/admin/moderation/comments/" + outroId + "/remove")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "acomment-remove-4")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "curto"))))
			.andExpect(status().isBadRequest());
		assertThat(one("select status from comments where id = ?", outroId)).isEqualTo("ACTIVE");
	}

	@Test
	void quemNaoEAdminNaoRemoveComentario() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var autor = auth.authenticate("acomment_autor2");
		var pessoa = auth.authenticate("acomment_pessoa");
		String postId = publish(autor, "Post com comentário protegido.", "acomment-post-2");
		MvcResult comment = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "acomment-comment-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "Comentário que fica no ar."))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
		String commentId = objectMapper.readTree(comment.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(post("/admin/moderation/comments/" + commentId + "/remove")
				.header("X-Session-Token", pessoa.token())
				.header("Idempotency-Key", "acomment-remove-5")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isForbidden());
		assertThat(one("select status from comments where id = ?", commentId)).isEqualTo("ACTIVE");
	}

	private String publish(TestAuthHelper.AuthSession session, String content, String idem) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", idem)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", content, "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String one(String sql, Object... args) {
		List<String> rows = jdbc.queryForList(sql, String.class, args);
		return rows.isEmpty() ? null : rows.getFirst();
	}
}