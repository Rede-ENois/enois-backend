package com.socialcoisa.rede.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * Bateria de segurança da seção 18, no que toca o isolamento entre grupos.
 *
 * O que este teste tranca:
 *
 * - membro de um grupo, sabendo os ids de outro, não lê tópicos, publicações, comentários nem a
 *   lista de membros do outro (403);
 * - interagir cruzando o contexto (comentar/reagir num post de outro grupo usando o id do grupo A)
 *   é 404 — o grupo do post é conferido antes de qualquer coisa;
 * - quem é removido perde o acesso NA HORA, sem depender de nova sessão;
 * - capa em HTML passa pelo sanitizador: script não é guardado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("critico")
class GroupSecurityTests {

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

	@Test
	void membroDeUmGrupoNaoLeNemInterageComOOutro() throws Exception {
		var dono = authHelper().authenticate("gsec-dono");
		var membroA = authHelper().authenticate("gsec-membro-a");
		String grupoA = createGroup(dono, "Grupo Seguro A", "gsec-create-a");
		String grupoB = createGroup(dono, "Grupo Seguro B", "gsec-create-b");
		join(membroA, grupoA, "gsec-join-a");
		String topicoB = topic(dono, grupoB, "Geral B", "gsec-topic-b");
		String postB = publish(dono, grupoB, topicoB, "Conteudo de B", "gsec-post-b");

		// Membro de A, com os ids de B na mão, não lê nada de B.
		mockMvc.perform(get("/groups/" + grupoB + "/topics").header("X-Session-Token", membroA.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/groups/" + grupoB + "/posts").header("X-Session-Token", membroA.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/groups/" + grupoB + "/posts/" + postB + "/comments")
				.header("X-Session-Token", membroA.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/groups/" + grupoB + "/members").header("X-Session-Token", membroA.token()))
			.andExpect(status().isForbidden());

		// E interagir cruzando o contexto é 404 (o grupo do post é conferido).
		mockMvc.perform(post("/groups/" + grupoA + "/posts/" + postB + "/comments")
				.header("X-Session-Token", membroA.token())
				.header("Idempotency-Key", "gsec-cross-comment")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "cruzando"))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));

		mockMvc.perform(post("/groups/" + grupoA + "/posts/" + postB + "/reactions")
				.header("X-Session-Token", membroA.token())
				.header("Idempotency-Key", "gsec-cross-reaction")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "LIKE"))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
	}

	@Test
	void removidoPerdeOConteudoNaHora() throws Exception {
		var dono = authHelper().authenticate("gsec-rem-dono");
		var membro = authHelper().authenticate("gsec-rem-membro");
		String groupId = createGroup(dono, "Grupo Remocao Segura", "gsec-create-rem");
		join(membro, groupId, "gsec-join-rem");
		String topicId = topic(dono, groupId, "Geral", "gsec-topic-rem");
		publish(dono, groupId, topicId, "Conteudo interno", "gsec-post-rem");

		mockMvc.perform(get("/groups/" + groupId + "/posts").header("X-Session-Token", membro.token()))
			.andExpect(status().isOk());

		// O dono remove; a MESMA sessão deixa de ver o conteúdo imediatamente.
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/groups/" + groupId + "/members/" + membro.userId())
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "gsec-remove-rem"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/groups/" + groupId + "/posts").header("X-Session-Token", membro.token()))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/groups/" + groupId + "/topics")
				.header("X-Session-Token", membro.token())
				.header("Idempotency-Key", "gsec-topic-after-remove")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Nao posso"))))
			.andExpect(status().isForbidden());
	}

	@Test
	void capaEmHtmlNaoGuardaScript() throws Exception {
		var dono = authHelper().authenticate("gsec-capa-dono");
		String groupId = createGroup(dono, "Grupo Capa Segura", "gsec-create-capa");

		MvcResult update = mockMvc.perform(patch("/groups/" + groupId)
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "gsec-banner-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"bannerHtml", "<script>alert('x')</script><div>capa</div>"))))
			.andExpect(status().isOk())
			.andReturn();

		String banner = objectMapper.readTree(update.getResponse().getContentAsString()).get("bannerHtml").asText();
		assertThat(banner).doesNotContainIgnoringCase("<script").contains("capa");
	}
}
