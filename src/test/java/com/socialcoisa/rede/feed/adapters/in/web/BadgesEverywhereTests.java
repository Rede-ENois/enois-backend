package com.socialcoisa.rede.feed.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Os emblemas acompanham o nome em TODO lugar — não só no perfil.
 *
 * O perfil sempre mostrou a lista (até 5); o feed, os comentários, o blog e a página do criador
 * mostravam só o primeiro. O que este teste tranca: a publicação e o comentário saem com
 * `authorBadges` com os cinco, na ordem escolhida — e o campo antigo (`authorBadge`) continua sendo
 * o primeiro, para uma versão antiga do app.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BadgesEverywhereTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private static List<String> cincoEmblemas() {
		return List.of(
			"https://cdn.test/emblema-1.png",
			"https://cdn.test/emblema-2.png",
			"https://cdn.test/emblema-3.png",
			"https://cdn.test/emblema-4.png",
			"https://cdn.test/emblema-5.png");
	}

	@Test
	void publicacaoEComentarioCarregamOsCincoEmblemas() throws Exception {
		var autor = auth.authenticate("badges-autor");
		List<String> badges = cincoEmblemas();

		mockMvc.perform(put("/me/badges")
				.header("X-Session-Token", autor.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("badges", badges))))
			.andExpect(status().isOk());

		// A publicação (resposta da escrita já é o PostView) leva a lista inteira.
		MvcResult publicado = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "badges-pub-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Post com cinco emblemas.", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorBadges.length()").value(5))
			.andExpect(jsonPath("$.authorBadges[0]").value(badges.get(0)))
			.andExpect(jsonPath("$.authorBadges[4]").value(badges.get(4)))
			.andExpect(jsonPath("$.authorBadge").value(badges.get(0)))
			.andReturn();
		String postId = objectMapper.readTree(publicado.getResponse().getContentAsString()).get("id").asText();

		// O comentário também.
		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "badges-comment-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"content", "Comentario com os mesmos emblemas.", "mediaIds", List.of(),
					"mentionedUserIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorBadges.length()").value(5));

		// Quem não tem emblema: lista vazia, campo antigo nulo.
		var semEmblema = auth.authenticate("badges-sem");
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", semEmblema.token())
				.header("Idempotency-Key", "badges-pub-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Sem emblema nenhum.", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorBadges.length()").value(0))
			.andExpect(jsonPath("$.authorBadge").doesNotExist());
	}
}
