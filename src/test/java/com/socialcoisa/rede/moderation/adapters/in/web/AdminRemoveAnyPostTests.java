package com.socialcoisa.rede.moderation.adapters.in.web;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * O admin pode remover QUALQUER publicação pelo id — sem depender de denúncia — e a resposta DIZ o
 * que aconteceu ({@code REMOVED}, {@code NOT_FOUND}, {@code ALREADY_OFFLINE}).
 *
 * <p>Antes a única remoção de post no painel era a em lote da fila de suspeitos: um admin que visse
 * um post problemático no feed não tinha por onde agir, e a remoção silenciosa devolvia sucesso
 * mesmo quando nada aconteceu — a origem do "Operação conflita com o estado atual dos dados" que
 * o painel mostrava sem explicar.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminRemoveAnyPostTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@DynamicPropertySource
	static void adminAllowlist(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "aremove_admin@test.com");
	}

	@Test
	void adminRemoveQualquerPostEAPrespostaDizOQueAconteceu() throws Exception {
		var author = new TestAuthHelper(mockMvc, objectMapper).authenticate("aremove_autor");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("aremove_admin");

		MvcResult created = publish(author, "Post que o admin vai remover pelo id.", "aremove-post");
		String postId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
		String authorToken = author.token();

		// Remoção administrativa pelo id: 200 e status REMOVED.
		mockMvc.perform(post("/admin/posts/" + postId + "/remove")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("note", "Remocao testada pelo painel do admin."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REMOVED"));

		// O post sai do ar para quem o lê.
		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", authorToken))
			.andExpect(status().isNotFound());

		// Remover de novo: não é erro — é ALREADY_OFFLINE, com explicação.
		mockMvc.perform(post("/admin/posts/" + postId + "/remove")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("note", "Remocao repetida do mesmo post."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ALREADY_OFFLINE"));

		// Id que não existe: 404 com status NOT_FOUND, não um 409 genérico.
		mockMvc.perform(post("/admin/posts/post_inexistente_123/remove")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("note", "Remocao de post que nao existe."))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.status").value("NOT_FOUND"));
	}

	@Test
	void moderadorNaoPodeRemoverPelaRotaDeAdmin() throws Exception {
		var moderator = new TestAuthHelper(mockMvc, objectMapper).authenticate("aremove_mod");

		// A conta do teste não é admin nem moderador: 403.
		mockMvc.perform(post("/admin/posts/post_qualquer/remove")
				.header("X-Session-Token", moderator.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("note", "Tentativa de quem nao e admin."))))
			.andExpect(status().isForbidden());
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
