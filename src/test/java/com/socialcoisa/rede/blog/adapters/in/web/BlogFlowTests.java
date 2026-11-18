package com.socialcoisa.rede.blog.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * O cantinho: blog proprio em /blog/{username}, posts que NAO entram no feed global, inscricao para
 * acompanhar e notificacao para quem segue.
 *
 * O que este teste tranca: (1) o post do blog nao aparece no "Tudo" nem para quem segue; (2) aparece
 * no "Para voce" SO de quem se inscreveu; (3) o ponteiro no feed leva a tag "blog"; (4) o corpo em
 * HTML sai sanitizado e o markdown fica como texto (o front formata); (5) so o dono edita/apaga.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BlogFlowTests {

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
	void blogPostReachesOnlySubscribersForYouAndNeverTheAllTab() throws Exception {
		var author = auth.authenticate("blog_author1");
		var follower = auth.authenticate("blog_follower1");
		var stranger = auth.authenticate("blog_stranger1");

		String slug = saveBlog(author, "Meu cantinho", "Portfolio e diario");
		String postId = publish(author, "Primeiro post", "# Titulo\n\nTexto com **negrito**.", "MARKDOWN");

		// A pagina do blog e publica e mostra o post (sem sessao).
		mockMvc.perform(get("/blogs/" + slug))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Meu cantinho"))
			.andExpect(jsonPath("$.items[0].title").value("Primeiro post"))
			.andExpect(jsonPath("$.items[0].bodyFormat").value("MARKDOWN"))
			.andExpect(jsonPath("$.subscribed").value(false))
			.andExpect(jsonPath("$.owner").value(false));

		// O ponteiro no feed existe, e do tipo BLOG e leva a tag "blog".
		mockMvc.perform(get("/feed/posts/" + postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("BLOG"))
			.andExpect(jsonPath("$.tags[0]").value("blog"));

		// Antes de se inscrever: nao aparece em lugar nenhum para o seguidor.
		assertNotInFeed(follower, "FOR_YOU", postId);
		assertNotInFeed(follower, "ALL", postId);

		// Inscreve-se: o proximo post chega no "Para voce" dele e continua FORA do "Tudo".
		mockMvc.perform(post("/blogs/" + slug + "/subscription").header("X-Session-Token", follower.token()))
			.andExpect(status().isOk());
		String secondPost = publish(author, "Segundo post", "so para quem segue", "MARKDOWN");
		assertInFeed(follower, "FOR_YOU", secondPost);
		assertNotInFeed(follower, "ALL", secondPost);

		// Quem nao segue continua sem ver.
		assertNotInFeed(stranger, "FOR_YOU", secondPost);

		// E o seguidor foi avisado da novidade.
		MvcResult notifications = mockMvc.perform(get("/notifications")
				.header("X-Session-Token", follower.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(notifications.getResponse().getContentAsString()).contains("NEW_BLOG_POST");

		// Desinscrever tira do "Para voce".
		mockMvc.perform(delete("/blogs/" + slug + "/subscription").header("X-Session-Token", follower.token()))
			.andExpect(status().isOk());
		assertNotInFeed(follower, "FOR_YOU", secondPost);
	}

	@Test
	void htmlBodyIsSanitizedAndOwnershipIsEnforced() throws Exception {
		var author = auth.authenticate("blog_author2");
		var other = auth.authenticate("blog_other2");

		String slug = saveBlog(author, "Cantinho", null);
		String postId = publish(author, "Post com HTML",
			"<h1>Ola</h1><script>alert(1)</script><p onclick=\"x()\">corpo</p>", "HTML");

		// O HTML guardado perdeu script e handler, e o post sai do blog assim.
		MvcResult blog = mockMvc.perform(get("/blogs/" + slug)).andExpect(status().isOk()).andReturn();
		JsonNode items = objectMapper.readTree(blog.getResponse().getContentAsString()).get("items");
		String body = items.get(0).get("body").asText();
		assertThat(body).contains("<h1>Ola</h1>").contains("corpo");
		assertThat(body).doesNotContain("<script").doesNotContain("onclick");

		// Outra pessoa nao edita nem apaga o post do blog alheio.
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.patch("/blogs/me/posts/" + postId)
				.header("X-Session-Token", other.token())
				.header("Idempotency-Key", "blog-edit-other")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "invadido", "body", "x", "format", "MARKDOWN"))))
			.andExpect(status().isForbidden());
		mockMvc.perform(delete("/blogs/me/posts/" + postId).header("X-Session-Token", other.token()))
			.andExpect(status().isForbidden());

		// O autor apaga: o post sai do blog e o ponteiro sai do feed.
		mockMvc.perform(delete("/blogs/me/posts/" + postId).header("X-Session-Token", author.token()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/blogs/" + slug))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void publishingWithoutABlogCreatesOneWithTheUsername() throws Exception {
		var author = auth.authenticate("blog_lazy1");

		publish(author, "Direto ao ponto", "texto simples", "MARKDOWN");

		// O blog nasceu sozinho, com o nome de usuario como endereco.
		MvcResult mine = mockMvc.perform(get("/blogs/me").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		String slug = objectMapper.readTree(mine.getResponse().getContentAsString()).get("slug").asText();

		mockMvc.perform(get("/blogs/" + slug))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].title").value("Direto ao ponto"));
	}

	// --- helpers ---

	private String saveBlog(TestAuthHelper.AuthSession author, String title, String description) throws Exception {
		MvcResult saved = mockMvc.perform(put("/blogs/me")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "blog-save-" + author.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(blogBody(title, description))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty()) // resposta de escrita nao carrega a lista de posts
			.andReturn();
		return objectMapper.readTree(saved.getResponse().getContentAsString()).get("slug").asText();
	}

	private java.util.Map<String, Object> blogBody(String title, String description) {
		java.util.Map<String, Object> body = new java.util.HashMap<>();
		body.put("title", title);
		body.put("description", description);
		body.put("themeHtml", "<div class=\"capa\">meu tema</div>");
		return body;
	}

private String publish(TestAuthHelper.AuthSession author, String title, String body, String format)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/blogs/me/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "blog-post-" + title + "-" + author.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", title, "body", body, "format", format))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void assertInFeed(TestAuthHelper.AuthSession viewer, String tab, String postId) throws Exception {
		assertThat(feedIds(viewer, tab)).contains(postId);
	}

	private void assertNotInFeed(TestAuthHelper.AuthSession viewer, String tab, String postId) throws Exception {
		assertThat(feedIds(viewer, tab)).doesNotContain(postId);
	}

	private List<String> feedIds(TestAuthHelper.AuthSession viewer, String tab) throws Exception {
		MvcResult result = mockMvc.perform(get("/feed")
				.header("X-Session-Token", viewer.token())
				.param("tab", tab)
				.param("limit", "20"))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
		return items.valueStream().map(node -> node.get("id").asText()).toList();
	}
}
