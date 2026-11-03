package com.socialcoisa.rede.feed.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** Republicar: ponteiro sem opinião ou citação com opinião, sem duplicata, sempre no original. */
@SpringBootTest
@AutoConfigureMockMvc
class RepostFlowTests {

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
	void pureRepostPointsAtTheOriginalBlocksDuplicatesAndCanBeUndone() throws Exception {
		var author = auth.authenticate("repost_author_" + System.nanoTime());
		var reposter = auth.authenticate("repost_user_" + System.nanoTime());
		var quoter = auth.authenticate("repost_quoter_" + System.nanoTime());
		var other = auth.authenticate("repost_other_" + System.nanoTime());

		String originalId = publish(author, "Conteúdo original que vale republicar.");

		// Sem opinião não nasce post novo: a resposta é o próprio original.
		mockMvc.perform(post("/feed/posts/{id}/repost", originalId)
				.header("X-Session-Token", reposter.token()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(originalId))
			.andExpect(jsonPath("$.repostOf").doesNotExist())
			.andExpect(jsonPath("$.content").value("Conteúdo original que vale republicar."));

		// A mesma pessoa não republica o mesmo post duas vezes.
		mockMvc.perform(post("/feed/posts/{id}/repost", originalId)
				.header("X-Session-Token", reposter.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_REPOSTED"));

		// Republicar uma citação aponta para o ORIGINAL (sem corrente de créditos).
		String quoteId = quote(quoter, originalId, "Assino embaixo.");
		mockMvc.perform(post("/feed/posts/{id}/repost", quoteId)
				.header("X-Session-Token", other.token()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(originalId));

		// Desfazer libera republicar de novo.
		mockMvc.perform(delete("/feed/posts/{id}/repost", originalId)
				.header("X-Session-Token", reposter.token()))
			.andExpect(status().isOk());
		mockMvc.perform(post("/feed/posts/{id}/repost", originalId)
				.header("X-Session-Token", reposter.token()))
			.andExpect(status().isCreated());
	}

	@Test
	void repostWithOpinionCarriesTheCommentAndQuotesTheOriginal() throws Exception {
		var author = auth.authenticate("repost_quote_author_" + System.nanoTime());
		var reposter = auth.authenticate("repost_quote_user_" + System.nanoTime());

		String originalId = publish(author, "Texto original que será citado no repost com opinião.");
		String opinion = "Discordo em parte: o ponto principal é bom, mas o contexto muda tudo.";

		MvcResult created = mockMvc.perform(post("/feed/posts/{id}/repost", originalId)
				.header("X-Session-Token", reposter.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("opinion", opinion))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.repostOf").value(originalId))
			.andExpect(jsonPath("$.repostWithOpinion").value(true))
			.andExpect(jsonPath("$.content").value(opinion))
			.andReturn();
		String repostId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asString();

		// Na leitura, o original vem citado (conteúdo e mídias) para o card montar a citação.
		mockMvc.perform(get("/feed/posts/{id}", repostId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.repostWithOpinion").value(true))
			.andExpect(jsonPath("$.repostOfContent")
				.value("Texto original que será citado no repost com opinião."))
			.andExpect(jsonPath("$.repostOfMediaIds").isArray());

		// Opinião acima de 600 caracteres é recusada com mensagem própria. (Texto variado:
		// PlainText.defuse colapsa corridas de 13+ do mesmo caractere.)
		String longOpinion = ("Esta e uma opiniao longa sobre o post e o contexto. ").repeat(20).substring(0, 601);
		var another = auth.authenticate("repost_quote_long_" + System.nanoTime());
		mockMvc.perform(post("/feed/posts/{id}/repost", originalId)
				.header("X-Session-Token", another.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("opinion", longOpinion))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("OPINION_TOO_LONG"));
	}

	@Test
	void editingARepostWithOpinionKeepsTheQuotedOriginal() throws Exception {
		var author = auth.authenticate("repost_edit_author_" + System.nanoTime());
		var reposter = auth.authenticate("repost_edit_user_" + System.nanoTime());

		String originalContent = "Texto original que continua citado depois da edição.";
		String originalId = publish(author, originalContent);
		String repostId = quote(reposter, originalId, "Comentário antes de editar.");

		// Editar só o comentário não pode soltar a citação do original.
		mockMvc.perform(patch("/feed/posts/{id}", repostId)
				.header("X-Session-Token", reposter.token())
				.header("Idempotency-Key", "repost-edit-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "Comentário depois de editar."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("Comentário depois de editar."))
			.andExpect(jsonPath("$.repostOf").value(originalId))
			.andExpect(jsonPath("$.repostOfAuthor").value(username(author)))
			.andExpect(jsonPath("$.repostWithOpinion").value(true));

		// E a leitura seguinte (do banco) continua com o original citado.
		mockMvc.perform(get("/feed/posts/{id}", repostId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("Comentário depois de editar."))
			.andExpect(jsonPath("$.repostOf").value(originalId))
			.andExpect(jsonPath("$.repostWithOpinion").value(true))
			.andExpect(jsonPath("$.repostOfContent").value(originalContent));
	}

	@Test
	void ephemeralPostsCannotBeReposted() throws Exception {
		var author = auth.authenticate("repost_moment_" + System.nanoTime());
		var reposter = auth.authenticate("repost_moment_user_" + System.nanoTime());

		MvcResult moment = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "repost-moment-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "MOMENT", "content", "Um momento que some em 24h.", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		String momentId = objectMapper.readTree(moment.getResponse().getContentAsString()).get("id").asString();

		mockMvc.perform(post("/feed/posts/{id}/repost", momentId)
				.header("X-Session-Token", reposter.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NOT_REPOSTABLE"));
	}

	@Test
	void articlesCirculateAsPointersOrAsQuotesSavedAsInsight() throws Exception {
		var author = auth.authenticate("repost_article_" + System.nanoTime());
		var reposter = auth.authenticate("repost_article_user_" + System.nanoTime());
		var other = auth.authenticate("repost_article_other_" + System.nanoTime());

		String articleId = publishType(author, Map.of("topic", "TECNOLOGIA", "type", "ARTICLE", "content", "Titulo do artigo citado",
			"articleBody", "Corpo que mora em outra tabela.", "tags", List.of(), "mediaIds", List.of()));

		// Repost sem opinião é ponteiro para o original, então o artigo circula com o corpo inteiro.
		mockMvc.perform(post("/feed/posts/{id}/repost", articleId)
				.header("X-Session-Token", reposter.token()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(articleId));

		String quoteId = quote(reposter, articleId, "Vale a leitura.");
		mockMvc.perform(get("/feed/posts/{id}", quoteId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("INSIGHT"))
			.andExpect(jsonPath("$.content").value("Vale a leitura."))
			.andExpect(jsonPath("$.repostOf").value(articleId))
			.andExpect(jsonPath("$.repostOfContent").value("Titulo do artigo citado"));

		mockMvc.perform(post("/feed/posts/{id}/repost", articleId)
				.header("X-Session-Token", reposter.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("opinion", "De novo."))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_REPOSTED"));

		// A citação é INSIGHT, mas republicá-la sem opinião aponta para o artigo original.
		mockMvc.perform(post("/feed/posts/{id}/repost", quoteId)
				.header("X-Session-Token", other.token()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(articleId));
	}

	@Test
	void pagesAndBlogPointersCirculateAsPointersOrAsQuotes() throws Exception {
		var author = auth.authenticate("repost_page_" + System.nanoTime());
		var reposter = auth.authenticate("repost_page_user_" + System.nanoTime());

		String pageId = publishType(author, Map.of("topic", "TECNOLOGIA", "type", "PAGE", "content", "",
			"pageHtml", "<p>Mini pagina</p>", "tags", List.of(), "mediaIds", List.of()));
		String blogId = publishType(author, Map.of("topic", "TECNOLOGIA", "type", "BLOG", "content", "Post do blog citado",
			"tags", List.of(), "mediaIds", List.of()));

		for (String id : List.of(pageId, blogId)) {
			mockMvc.perform(post("/feed/posts/{id}/repost", id)
					.header("X-Session-Token", reposter.token()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(id));

			String quoteId = quote(reposter, id, "Olha isso aqui " + id);
			mockMvc.perform(get("/feed/posts/{id}", quoteId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type").value("INSIGHT"))
				.andExpect(jsonPath("$.repostOf").value(id));
		}
	}

	private String quote(TestAuthHelper.AuthSession session, String postId, String opinion) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts/{id}/repost", postId)
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("opinion", opinion))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}

	private String publishType(TestAuthHelper.AuthSession session, Map<String, Object> body) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "repost-type-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}

	private String publish(TestAuthHelper.AuthSession session, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "repost-pub-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", content, "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}

	private String username(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult me = mockMvc.perform(get("/me").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(me.getResponse().getContentAsString()).get("username").asString();
	}
}
