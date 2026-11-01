package com.socialcoisa.rede.groups;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Moderação local do grupo.
 *
 * O que este teste tranca:
 *
 * - o autor apaga o que escreveu; outro membro comum NÃO apaga o post alheio (403);
 * - quem administra apaga comentário de qualquer um, e o contador da publicação acompanha;
 * - o dono encerra o grupo (só ele): sai da descoberta e o feed privado deixa de abrir (404);
 * - só o autor edita o próprio comentário (quem modera e outro membro recebem 403);
 * - o conteúdo removido some das listagens (não fica na tela como se nada tivesse acontecido).
 */
@SpringBootTest
@AutoConfigureMockMvc
class GroupModerationTests {

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

	@Test
	void autorApagaOProprioEOutroMembroNao() throws Exception {
		var dono = authHelper().authenticate("gmod-dono");
		var membro = authHelper().authenticate("gmod-membro");
		var outro = authHelper().authenticate("gmod-outro");
		String groupId = createGroup(dono, "Grupo Moderacao", "gmod-create-1");
		join(membro, groupId, "gmod-join-1");
		join(outro, groupId, "gmod-join-outro");
		String topicId = topic(dono, groupId, "Geral", "gmod-topic-1");
		String postDoMembro = publish(membro, groupId, topicId, "Post do membro", "gmod-post-1");

		mockMvc.perform(delete("/groups/" + groupId + "/posts/" + postDoMembro)
				.header("X-Session-Token", outro.token())
				.header("Idempotency-Key", "gmod-remove-outro"))
			.andExpect(status().isForbidden());

		mockMvc.perform(delete("/groups/" + groupId + "/posts/" + postDoMembro)
				.header("X-Session-Token", membro.token())
				.header("Idempotency-Key", "gmod-remove-autor"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REMOVED"));

		mockMvc.perform(get("/groups/" + groupId + "/posts").header("X-Session-Token", dono.token()))
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void administracaoRemoveComentarioEOCountadorAcompanha() throws Exception {
		var dono = authHelper().authenticate("gmod-dono2");
		var membro = authHelper().authenticate("gmod-membro2");
		String groupId = createGroup(dono, "Grupo Comentarios", "gmod-create-2");
		join(membro, groupId, "gmod-join-2");
		String topicId = topic(dono, groupId, "Geral", "gmod-topic-2");
		String postId = publish(dono, groupId, topicId, "Post para moderar", "gmod-post-2");
		String commentId = comment(membro, groupId, postId, "Comentario removivel", "gmod-comment-2");

		mockMvc.perform(delete("/groups/" + groupId + "/posts/" + postId + "/comments/" + commentId)
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "gmod-remove-comment"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/groups/" + groupId + "/posts/" + postId + "/comments")
				.header("X-Session-Token", dono.token()))
			.andExpect(jsonPath("$.items.length()").value(0));

		mockMvc.perform(get("/groups/" + groupId + "/posts").header("X-Session-Token", dono.token()))
			.andExpect(jsonPath("$.items[0].comments").value(0));
	}

	@Test
	void soOAutorEditaOProprioComentario() throws Exception {
		var dono = authHelper().authenticate("gmod-dono4");
		var membro = authHelper().authenticate("gmod-membro4");
		var outro = authHelper().authenticate("gmod-outro4");
		String groupId = createGroup(dono, "Grupo Edicao", "gmod-create-4");
		join(membro, groupId, "gmod-join-4");
		join(outro, groupId, "gmod-join-outro4");
		String topicId = topic(dono, groupId, "Geral", "gmod-topic-4");
		String postId = publish(dono, groupId, topicId, "Post para editar", "gmod-post-4");
		String commentId = comment(membro, groupId, postId, "Comentario original", "gmod-comment-4");
		String path = "/groups/" + groupId + "/posts/" + postId + "/comments/" + commentId;

		mockMvc.perform(patch(path)
				.header("X-Session-Token", outro.token())
				.header("Idempotency-Key", "gmod-edit-outro")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "tentativa"))))
			.andExpect(status().isForbidden());

		// Quem modera remove, mas não reescreve o que o outro disse.
		mockMvc.perform(patch(path)
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "gmod-edit-dono")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "tentativa"))))
			.andExpect(status().isForbidden());

		mockMvc.perform(patch(path)
				.header("X-Session-Token", membro.token())
				.header("Idempotency-Key", "gmod-edit-vazio")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "   "))))
			.andExpect(status().isBadRequest());

		mockMvc.perform(patch(path)
				.header("X-Session-Token", membro.token())
				.header("Idempotency-Key", "gmod-edit-autor")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "Comentario corrigido"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(commentId))
			.andExpect(jsonPath("$.content").value("Comentario corrigido"));

		mockMvc.perform(get("/groups/" + groupId + "/posts/" + postId + "/comments")
				.header("X-Session-Token", dono.token()))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].content").value("Comentario corrigido"));

		mockMvc.perform(delete(path)
				.header("X-Session-Token", membro.token())
				.header("Idempotency-Key", "gmod-edit-remove"))
			.andExpect(status().isOk());

		mockMvc.perform(patch(path)
				.header("X-Session-Token", membro.token())
				.header("Idempotency-Key", "gmod-edit-removido")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "tarde demais"))))
			.andExpect(status().isNotFound());
	}

	@Test
	void donoEncerraOGrupoESomeParaLeitura() throws Exception {
		var dono = authHelper().authenticate("gmod-dono3");
		var membro = authHelper().authenticate("gmod-membro3");
		String groupId = createGroup(dono, "Grupo Encerrado", "gmod-create-3");
		join(membro, groupId, "gmod-join-3");

		mockMvc.perform(post("/groups/" + groupId + "/close")
				.header("X-Session-Token", membro.token())
				.header("Idempotency-Key", "gmod-close-membro"))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/groups/" + groupId + "/close")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "gmod-close-dono"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CLOSED"));

		mockMvc.perform(get("/groups").param("query", "Encerrado"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0));

		mockMvc.perform(get("/groups/" + groupId + "/topics").header("X-Session-Token", dono.token()))
			.andExpect(status().isNotFound());
	}
}
