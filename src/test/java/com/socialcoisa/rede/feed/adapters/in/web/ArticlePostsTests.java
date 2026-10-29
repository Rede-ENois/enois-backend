package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Artigo: categoria propria, com o texto numa tabela propria (`articles`).
 *
 * O que estes testes fixam: artigo tem teto maior que post (4.000 x 1.200), o texto nao viaja na
 * listagem nem na resposta da escrita (ela passa pela idempotencia, cujo registro de resposta e uma
 * coluna de texto finita), e os tipos novos de publicacao publicam como qualquer outro.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ArticlePostsTests {

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
	void articleKeepsBodyInItsOwnTableAndOutOfTheListings() throws Exception {
		var author = auth.authenticate("user_article_owner1");
		String body = "Texto do artigo. ".repeat(200);

		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "article-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("type", "ARTICLE", "content", "Um artigo de verdade", "articleBody", body))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.type").value("ARTICLE"))
			.andExpect(jsonPath("$.content").value("Um artigo de verdade"))
			.andReturn();

		JsonNode node = objectMapper.readTree(created.getResponse().getContentAsString());
		assertThat(node.get("articleBody") == null || node.get("articleBody").isNull()).isTrue();

		// O texto vem na leitura do post unico...
		assertThat(readArticleBody(author, node.get("id").asText())).isEqualTo(body.trim());

		// ...e nao na listagem (nem o card precisa dele: o resumo esta em `content`).
		MvcResult feed = mockMvc.perform(get("/feed").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		for (JsonNode item : objectMapper.readTree(feed.getResponse().getContentAsString()).get("items")) {
			JsonNode article = item.get("articleBody");
			assertThat(article == null || article.isNull()).isTrue();
		}
	}

	@Test
	void articleBodyLimitIsTenThousand() throws Exception {
		var author = auth.authenticate("user_article_owner2");

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "article-limit")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("type", "ARTICLE", "content", "Titulo", "articleBody", "a".repeat(10_001)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("ARTICLE_BODY_TOO_LONG"));
	}

	@Test
	void articleWithoutBodyIsRejected() throws Exception {
		var author = auth.authenticate("user_article_owner3");

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "article-nobody")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("type", "ARTICLE", "content", "Titulo sem corpo"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ARTICLE_BODY"));
	}

	@Test
	void otherTypesIgnoreTheArticleBody() throws Exception {
		var author = auth.authenticate("user_article_owner4");

		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "article-ignored")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("type", "INSIGHT", "content", "Post comum", "articleBody", "texto que nao deve ser guardado"))))
			.andExpect(status().isCreated())
			.andReturn();

		String postId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
		assertThat(readArticleBody(author, postId)).isNull();
	}

	@Test
	void editingAnArticleRequiresItsBody() throws Exception {
		var author = auth.authenticate("user_article_owner5");
		String postId = publishArticle(author, "Titulo original", "Corpo original do artigo.", "article-edit-pub");

		mockMvc.perform(patch("/feed/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "article-edit-nobody")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("content", "Titulo revisado"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ARTICLE_BODY"));

		mockMvc.perform(patch("/feed/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "article-edit-body")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("content", "Titulo revisado", "articleBody", "Corpo revisado."))))
			.andExpect(status().isOk());

		assertThat(readArticleBody(author, postId)).isEqualTo("Corpo revisado.");
	}

	@Test
	void newPublicationTypesPublishLikeAnyOther() throws Exception {
		var author = auth.authenticate("user_article_owner6");

		for (String type : List.of("NEWS", "TUTORIAL", "REVIEW", "MOMENT")) {
			mockMvc.perform(post("/feed/posts")
					.header("X-Session-Token", author.token())
					.header("Idempotency-Key", "type-" + type)
					.contentType(MediaType.APPLICATION_JSON)
					.content(json(Map.of("type", type, "content", "Publicacao do tipo " + type))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.type").value(type));
		}
	}

	private String publishArticle(TestAuthHelper.AuthSession session, String title, String body, String key)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("type", "ARTICLE", "content", title, "articleBody", body))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String readArticleBody(TestAuthHelper.AuthSession session, String postId) throws Exception {
		MvcResult read = mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode node = objectMapper.readTree(read.getResponse().getContentAsString()).get("articleBody");
		return node == null || node.isNull() ? null : node.asText();
	}

	private String json(Map<String, Object> body) throws Exception {
		Map<String, Object> payload = new HashMap<>(body);
		payload.putIfAbsent("tags", List.of());
		payload.putIfAbsent("mediaIds", List.of());
		return objectMapper.writeValueAsString(payload);
	}
}
