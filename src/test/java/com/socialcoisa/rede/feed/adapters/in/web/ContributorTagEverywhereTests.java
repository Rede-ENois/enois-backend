package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import com.socialcoisa.rede.contributions.application.port.out.UserSealRepository;
import com.socialcoisa.rede.contributions.domain.ContributionCategory;
import com.socialcoisa.rede.contributions.domain.SealStatus;
import com.socialcoisa.rede.contributions.domain.SealType;
import com.socialcoisa.rede.contributions.domain.UserSeal;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A tag "Contribuinte" acompanha o nome no feed, na leitura do post e nos comentários — do mesmo
 * jeito que no perfil. Quem não tem selo ativo não recebe a tag.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContributorTagEverywhereTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private UserSealRepository seals;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private void concedeSelo(String userId) {
		seals.save(new UserSeal("seal_tag_" + System.nanoTime(), userId, SealType.AJUDANTE,
			ContributionCategory.CODE, SealStatus.ACTIVE, Instant.now(), null, null, null, null, 1));
	}

	@Test
	void publicacaoLeituraEComentarioCarregamATagDeContribuinte() throws Exception {
		var autor = auth.authenticate("contrib_tag_autor");
		concedeSelo(autor.userId());

		MvcResult publicado = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "contrib-tag-pub-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Post de contribuinte.", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorContributor").exists())
			.andReturn();
		String postId = objectMapper.readTree(publicado.getResponse().getContentAsString()).get("id").asString();

		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.authorContributor").exists());

		MvcResult comentario = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "contrib-tag-comment-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"content", "Comentário de contribuinte.", "mediaIds", List.of(),
					"mentionedUserIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorContributor").exists())
			.andReturn();
		String commentId = objectMapper.readTree(comentario.getResponse().getContentAsString()).get("id").asString();

		MvcResult listados = mockMvc.perform(get("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode items = objectMapper.readTree(listados.getResponse().getContentAsString()).get("items");
		JsonNode proprio = null;
		for (JsonNode item : items) {
			if (commentId.equals(item.path("id").asString())) {
				proprio = item;
				break;
			}
		}
		assertThat(proprio).as("comentário na listagem").isNotNull();
		assertThat(proprio.path("authorContributor").isMissingNode()).isFalse();
	}

	@Test
	void quemNaoTemSeloAtivoNaoRecebeATag() throws Exception {
		var autor = auth.authenticate("contrib_tag_sem");

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "contrib-tag-sem-pub")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Post sem selo.", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorContributor").doesNotExist());
	}
}
