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
import com.socialcoisa.rede.shared.html.SafeHtml;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Post do tipo PAGE: o autor cola um HTML e ele vira uma mini pagina. O backend guarda o HTML
 * ja sanitizado, entao o teste cobre publicacao, edicao e os limites — e confirma que HTML em
 * posts de outros tipos e descartado em vez de virar lixo no banco.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostPageTests {

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
	void publishesPageWithSanitizedHtml() throws Exception {
		var author = auth.authenticate("user_page_owner1");

		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "page-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("topic", "TECNOLOGIA",
					"type", "PAGE",
					"content", "Minha pagina",
					"pageHtml", "<h1>Ola</h1><script>fetch('https://malicioso.test')</script>"
						+ "<p onclick=\"alert(1)\" style=\"color:#123456\">corpo</p>"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.type").value("PAGE"))
			.andExpect(jsonPath("$.content").value("Minha pagina"))
			.andReturn();

		JsonNode node = objectMapper.readTree(created.getResponse().getContentAsString());
		String postId = node.get("id").asText();

		// A resposta da publicacao nao leva a pagina: um HTML grande nao cabe no registro de
		// idempotencia que guarda essa resposta (e quebrava a publicacao no banco de verdade).
		JsonNode createdPage = node.get("pageHtml");
		assertThat(createdPage == null || createdPage.isNull()).isTrue();

		// Quem quer a pagina pede o post unico — e recebe o HTML ja limpo.
		String pageHtml = readPage(author, postId);
		assertThat(pageHtml).contains("<h1>Ola</h1>").contains("corpo").contains("#123456");
		assertThat(pageHtml).doesNotContain("<script").doesNotContain("onclick").doesNotContain("malicioso.test");
	}

	@Test
	void feedListDoesNotCarryThePageBody() throws Exception {
		var author = auth.authenticate("user_page_list");
		String postId = publishPage(author, "Pagina do feed", "<p>corpo grande</p>", "page-list-pub");

		MvcResult feed = mockMvc.perform(get("/feed").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();

		JsonNode item = null;
		for (JsonNode candidate : objectMapper.readTree(feed.getResponse().getContentAsString()).get("items")) {
			if (postId.equals(candidate.get("id").asText())) {
				item = candidate;
			}
		}

		// O post da pagina esta na listagem (a marcacao do tipo vai junto), mas sem o corpo.
		assertThat(item).isNotNull();
		assertThat(item.get("type").asText()).isEqualTo("PAGE");
		assertThat(item.get("pageHtml") == null || item.get("pageHtml").isNull()).isTrue();
	}

	/**
	 * Pagina e uma coisa OU outra: o HTML e o conteudo do post, entao o texto nao e exigido. Antes
	 * disso, publicar uma pagina obrigava a inventar um "texto de post" que nao tinha funcao.
	 */
	@Test
	void pageWithoutPostTextIsAccepted() throws Exception {
		var author = auth.authenticate("user_page_notext");

		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "page-notext")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("topic", "TECNOLOGIA", "type", "PAGE", "pageHtml", "<h1>So a pagina</h1>"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.type").value("PAGE"))
			.andExpect(jsonPath("$.content").value(""))
			.andReturn();

		String postId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
		assertThat(readPage(author, postId)).isEqualTo("<h1>So a pagina</h1>");

		// Editar a pagina sem texto tambem passa.
		mockMvc.perform(patch("/feed/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "page-notext-edit")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("content", "", "pageHtml", "<h1>So a pagina v2</h1>"))))
			.andExpect(status().isOk());
		assertThat(readPage(author, postId)).isEqualTo("<h1>So a pagina v2</h1>");
	}

	@Test
	void postTextIsStillRequiredForOtherTypes() throws Exception {
		var author = auth.authenticate("user_post_notext");

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "post-notext")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content", "   "))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CONTENT"));
	}

	@Test
	void pageWithoutHtmlIsRejected() throws Exception {
		var author = auth.authenticate("user_page_owner2");

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "page-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("topic", "TECNOLOGIA", "type", "PAGE", "content", "Pagina sem corpo", "pageHtml", "   "))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PAGE_HTML"));
	}

	@Test
	void htmlSentOnAnotherTypeIsDiscarded() throws Exception {
		var author = auth.authenticate("user_page_owner3");

		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "page-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT",
					"content", "Post normal com html na mochila",
					"pageHtml", "<p>nao deveria ser guardado</p>"))))
			.andExpect(status().isCreated())
			.andReturn();

		JsonNode node = objectMapper.readTree(created.getResponse().getContentAsString());
		assertThat(readPage(author, node.get("id").asText())).isNull();
	}

	@Test
	void editReplacesPageAndRequiresIt() throws Exception {
		var author = auth.authenticate("user_page_owner4");
		String postId = publishPage(author, "Pagina original", "<p>versao 1</p>", "page-edit-pub");

		mockMvc.perform(patch("/feed/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "page-edit-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("content", "Pagina revisada", "pageHtml", "<p>versao 2</p><script>x()</script>"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("Pagina revisada"));

		// A edicao troca a pagina (o script do HTML novo nao sobrevive a limpeza).
		assertThat(readPage(author, postId)).isEqualTo("<p>versao 2</p>");

		// Editar uma pagina sem mandar HTML apagaria o corpo do post: o backend recusa.
		mockMvc.perform(patch("/feed/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "page-edit-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("content", "Pagina revisada"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PAGE_HTML"));
	}

	/**
	 * O teto de HTML e igual para todo mundo: duas contas comuns (sem reputacao, sem privilegio)
	 * publicam no mesmo tamanho, e o mesmo limite vale para as duas. Nao ha faixa de excecao.
	 */
	@Test
	void pageLimitIsTheSameForEveryUser() throws Exception {
		var first = auth.authenticate("user_page_owner5");
		var second = auth.authenticate("user_page_owner6");
		String big = "<p>" + "a".repeat(SafeHtml.MAX_PAGE_HTML - 10) + "</p>";

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", first.token())
				.header("Idempotency-Key", "page-5a")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("topic", "TECNOLOGIA", "type", "PAGE", "content", "Pagina grande", "pageHtml", big))))
			.andExpect(status().isCreated());

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", second.token())
				.header("Idempotency-Key", "page-5b")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("topic", "TECNOLOGIA", "type", "PAGE", "content", "Pagina grande", "pageHtml", big))))
			.andExpect(status().isCreated());

		String tooBig = "<p>" + "a".repeat(SafeHtml.MAX_PAGE_HTML) + "</p>";
		for (var author : List.of(first, second)) {
			mockMvc.perform(post("/feed/posts")
					.header("X-Session-Token", author.token())
					.header("Idempotency-Key", "page-5-" + author.userId())
					.contentType(MediaType.APPLICATION_JSON)
					.content(json(Map.of("topic", "TECNOLOGIA",
						"type", "PAGE",
						"content", "Pagina grande demais",
						"pageHtml", tooBig))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PAGE_HTML_TOO_LONG"));
		}
	}

	/** Le o post unico e devolve o HTML da pagina (nulo quando o post nao tem uma). */
	private String readPage(TestAuthHelper.AuthSession session, String postId) throws Exception {
		MvcResult read = mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode page = objectMapper.readTree(read.getResponse().getContentAsString()).get("pageHtml");
		return page == null || page.isNull() ? null : page.asText();
	}

	private String publishPage(TestAuthHelper.AuthSession session, String title, String html, String key)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("topic", "TECNOLOGIA", "type", "PAGE", "content", title, "pageHtml", html))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	/** Map.of nao aceita nulo; aqui os campos sao todos nao nulos, mas a lista de tags fica explicita. */
	private String json(Map<String, Object> body) throws Exception {
		Map<String, Object> payload = new HashMap<>(body);
		payload.putIfAbsent("tags", List.of());
		payload.putIfAbsent("mediaIds", List.of());
		return objectMapper.writeValueAsString(payload);
	}
}
