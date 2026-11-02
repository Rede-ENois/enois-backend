package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.feed.application.port.in.ModerationQuarantinePostUseCase;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Quarentena pelo lado de fora: enquanto o post está sob revisão, ninguém o vê — nem pelo feed, nem
 * pelo perfil, nem pelo link direto. Se a revisão libera, ele volta como estava.
 *
 * <p>É o que a rede precisa garantir: precaução sem exclusão. O post não some do banco, o autor não
 * é punido, e o conteúdo reaparece intacto quando uma pessoa conclui que não havia violação.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostQuarantineVisibilityTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ModerationQuarantinePostUseCase quarantine;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void aQuarantinedPostDisappearsFromEveryReadAndComesBackWhenReleased() throws Exception {
		String conta = "quar" + UUID.randomUUID().toString().substring(0, 8);
		var author = auth.authenticate(conta);
		Publicado publicado = publish(author, "Post que vai entrar em revisão " + conta);
		String postId = publicado.id();

		// No ar: o link direto abre.
		mockMvc.perform(get("/feed/posts/" + postId)).andExpect(status().isOk());

		assertThat(quarantine.quarantine("automod:conteudo", postId, "revisao automatica")).isTrue();

		// Sob revisão: some do detalhe, do perfil e do feed — para o autor também.
		mockMvc.perform(get("/feed/posts/" + postId))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
		mockMvc.perform(get("/users/" + publicado.username() + "/posts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + postId + "')]").doesNotExist());
		mockMvc.perform(get("/feed").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + postId + "')]").doesNotExist());

		// A revisão libera: o post volta exatamente como estava.
		assertThat(quarantine.release("mod_revisao", postId)).isTrue();
		mockMvc.perform(get("/feed/posts/" + postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(postId));
	}

	@Test
	void theAuthorStillSeesThePostInTheirOwnHistoryOfDeletion() throws Exception {
		// Quarentena não apaga: o autor pode apagar o próprio post enquanto ele está em revisão.
		String conta = "quardel" + UUID.randomUUID().toString().substring(0, 8);
		var author = auth.authenticate(conta);
		String postId = publish(author, "Post que o autor apaga durante a revisão " + conta).id();

		quarantine.quarantine("automod:conteudo", postId, "revisao automatica");

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/feed/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "quar-del-" + postId))
			.andExpect(status().isOk());
		mockMvc.perform(get("/feed/posts/" + postId)).andExpect(status().isNotFound());
	}

	private record Publicado(String id, String username) {
	}

	private Publicado publish(TestAuthHelper.AuthSession session, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "quar-" + content.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "INSIGHT",
					"content", content,
					"tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		return new Publicado(body.get("id").asText(), body.get("authorUsername").asText());
	}
}
