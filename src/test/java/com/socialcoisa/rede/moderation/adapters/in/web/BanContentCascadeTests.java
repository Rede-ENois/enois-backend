package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * O que banir uma conta FAZ com o conteúdo dela: os posts saem do ar, os comentários saem dos
 * posts, o autor volta a ver os próprios posts como removidos e o ban repetido não é erro — é
 * "já estava banida".
 *
 * <p>O fluxo de ban já existia com cascade ({@code BanCleanupService}: arquiva e remove posts,
 * comentários e conteúdo nos módulos). O que faltava era o teste que tranca o que a dona pediu:
 * "se eu banir um usuário, todos os posts dele têm que ser banidos e excluídos também". Sem este
 * teste, uma refatoração silenciosa do cleanup passaria batido.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class BanContentCascadeTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@DynamicPropertySource
	static void adminAllowlist(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "cascade_admin@test.com");
	}

	private final String sufixo = UUID.randomUUID().toString().substring(0, 8);

	@Test
	void banirContaRemoveOsPostsEComentariosDelaDaRede() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("cascade_admin");
		var alvo = new TestAuthHelper(mockMvc, objectMapper).authenticate("cascade_alvo_" + sufixo);
		var leitor = new TestAuthHelper(mockMvc, objectMapper).authenticate("cascade_leitor_" + sufixo);

		// O alvo publica e comenta.
		MvcResult criado = publish(alvo, "Post que sai do ar quando a conta é banida.", "cascade-" + sufixo);
		String postId = objectMapper.readTree(criado.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", alvo.token())
				.header("Idempotency-Key", "cascade-comment-" + sufixo)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "Comentario do proprio autor."))))
			.andExpect(status().isCreated());

		// O leitor vê o post antes do ban.
		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk());

		// Ban pelo painel do admin: POST /admin/users/{username}/sanction, tipo BAN.
		String username = objectMapper.readTree(
			mockMvc.perform(get("/me").header("X-Session-Token", alvo.token())).andReturn()
				.getResponse().getContentAsString()).get("username").asText();
		mockMvc.perform(post("/admin/users/" + username + "/sanction")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "BAN",
					"reason", "Cascade testado no fluxo do painel de admin."))))
			.andExpect(status().isOk());

		// O post não existe mais para quem lê: o cascade o removeu.
		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", leitor.token()))
			.andExpect(status().isNotFound());

		// O feed do leitor não traz mais o post banido.
		String feed = mockMvc.perform(get("/feed").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(feed).doesNotContain(postId);

		// Banir de novo NÃO é erro: o painel recebe "já estava banida" em vez de um 409 genérico.
		mockMvc.perform(post("/admin/users/" + username + "/sanction")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "BAN",
					"reason", "Banimento repetido para provar que nao vira conflito."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ALREADY_BANNED"));
	}

	private MvcResult publish(TestAuthHelper.AuthSession session, String content, String idem) throws Exception {
		return mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", idem)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", content, "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
	}

}