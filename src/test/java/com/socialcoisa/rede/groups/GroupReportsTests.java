package com.socialcoisa.rede.groups;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
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
 * Denúncias de conteúdo de grupo.
 *
 * O que este teste tranca: a publicação e o comentário do feed privado podem ser denunciados
 * (`GROUP_POST`/`GROUP_COMMENT`), e o painel de moderação — que é da PLATAFORMA — vê o texto e o
 * autor do conteúdo. Sem o leitor do grupo, a denúncia chegava sem conteúdo e a triagem por IA
 * julgava pelo id.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("critico")
class GroupReportsTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper authHelper() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	private String createGroup(TestAuthHelper.AuthSession owner, String name, String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/groups")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "joinMode", "OPEN"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void join(TestAuthHelper.AuthSession user, String groupId, String key) throws Exception {
		mockMvc.perform(post("/groups/" + groupId + "/join")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", key))
			.andExpect(status().isOk());
	}

	private String topic(TestAuthHelper.AuthSession admin, String groupId, String name, String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/groups/" + groupId + "/topics")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String publish(TestAuthHelper.AuthSession author, String groupId, String topicId, String content,
			String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/groups/" + groupId + "/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"topicId", topicId, "content", content, "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String comment(TestAuthHelper.AuthSession author, String groupId, String postId, String content,
			String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/groups/" + groupId + "/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", content))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String report(TestAuthHelper.AuthSession reporter, String targetType, String targetId, String key)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/reports")
				.header("X-Session-Token", reporter.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"targetType", targetType, "targetId", targetId, "reason", "SPAM",
					"description", "spam no grupo"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void denunciaDeConteudoDeGrupoChegaNaModeracaoComConteudoEAutor() throws Exception {
		var dono = authHelper().authenticate("grep-dono");
		var denunciante = authHelper().authenticate("grep-denunciante");
		var admin = authHelper().authenticate("group-admin");
		String groupId = createGroup(dono, "Grupo Denuncia", "grep-create-1");
		join(denunciante, groupId, "grep-join-1");
		String topicId = topic(dono, groupId, "Geral", "grep-topic-1");
		String postId = publish(dono, groupId, topicId, "Conteudo denunciavel do grupo", "grep-post-1");
		String commentId = comment(denunciante, groupId, postId, "Comentario denunciavel", "grep-comment-1");

		String reportDoPost = report(denunciante, "GROUP_POST", postId, "grep-report-post");
		mockMvc.perform(get("/admin/reports/" + reportDoPost).header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("Conteudo denunciavel do grupo"))
			.andExpect(jsonPath("$.authorId").value(dono.userId()));

		String reportDoComentario = report(denunciante, "GROUP_COMMENT", commentId, "grep-report-comment");
		mockMvc.perform(get("/admin/reports/" + reportDoComentario).header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("Comentario denunciavel"))
			.andExpect(jsonPath("$.authorId").value(denunciante.userId()));
	}
}
