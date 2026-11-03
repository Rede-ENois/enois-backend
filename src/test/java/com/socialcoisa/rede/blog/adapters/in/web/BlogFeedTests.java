package com.socialcoisa.rede.blog.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Feed Atom do blog (`GET /blogs/{slug}/feed.xml`): publico, sem sessao, um feed por blog, com
 * resumo em texto puro (nunca o HTML do corpo) e XML sempre bem formado.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BlogFeedTests {

	private static final String ATOM = "http://www.w3.org/2005/Atom";

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
	void feedListsThePostsOfThatBlogOnlyAsWellFormedAtomWithPlainTextSummary() throws Exception {
		var author = auth.authenticate("blog_feed_author");
		var other = auth.authenticate("blog_feed_other");
		String slug = saveBlog(author, "Diario & \"anotacoes\"", "Notas <rapidas>");
		String otherSlug = saveBlog(other, "Outro cantinho", null);
		String postId = publish(author, "Cafe & pao <com> manteiga",
			"<h1>Ola</h1><p>corpo do <b>post</b> com resumo</p>", "HTML");
		publish(other, "Post do outro blog", "nao pode aparecer no feed do primeiro", "MARKDOWN");

		MvcResult result = mockMvc.perform(get("/blogs/" + slug + "/feed.xml"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/atom+xml")))
			.andExpect(header().string("Cache-Control", "public, max-age=300"))
			.andReturn();
		String xml = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

		Document feed = parse(xml);
		Element root = feed.getDocumentElement();
		assertThat(root.getNamespaceURI()).isEqualTo(ATOM);
		assertThat(text(root, "title")).isEqualTo("Diario & \"anotacoes\"");
		assertThat(text(root, "subtitle")).isEqualTo("Notas <rapidas>");

		NodeList entries = feed.getElementsByTagNameNS(ATOM, "entry");
		assertThat(entries.getLength()).isEqualTo(1);
		Element entry = (Element) entries.item(0);
		assertThat(text(entry, "title")).isEqualTo("Cafe & pao <com> manteiga");
		assertThat(text(entry, "id")).endsWith("/blog/" + slug + "/" + postId);
		// Resumo em texto puro: sem tag do corpo HTML, com o texto que sobrou.
		String summary = text(entry, "summary");
		assertThat(summary).contains("Ola").contains("corpo do post com resumo");
		assertThat(summary).doesNotContain("<");
		assertThat(xml).doesNotContain("nao pode aparecer").doesNotContain("/blog/" + otherSlug);
	}

	@Test
	void feedIsPublicEvenWithoutAnyPostAndUnknownBlogIs404() throws Exception {
		var author = auth.authenticate("blog_feed_empty");
		String slug = saveBlog(author, "Ainda sem posts", null);

		MvcResult result = mockMvc.perform(get("/blogs/" + slug + "/feed.xml")).andExpect(status().isOk()).andReturn();
		Document feed = parse(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
		assertThat(feed.getElementsByTagNameNS(ATOM, "entry").getLength()).isZero();
		// Atom exige `updated` no feed, mesmo vazio.
		assertThat(text(feed.getDocumentElement(), "updated")).isNotBlank();

		mockMvc.perform(get("/blogs/nao_existe_feed/feed.xml"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BLOG_NOT_FOUND"));
	}

	@Test
	void controlCharactersInTheTitleNeverBreakTheXml() throws Exception {
		var author = auth.authenticate("blog_feed_ctrl");
		String slug = saveBlog(author, "Cantinho", null);
		publish(author, "Titulo com controle \u0001 e emoji 😀", "corpo simples", "MARKDOWN");

		MvcResult result = mockMvc.perform(get("/blogs/" + slug + "/feed.xml")).andExpect(status().isOk()).andReturn();
		Document feed = parse(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
		Element entry = (Element) feed.getElementsByTagNameNS(ATOM, "entry").item(0);
		assertThat(text(entry, "title")).startsWith("Titulo com controle").endsWith("😀").doesNotContain("\u0001");
	}

	private Document parse(String xml) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
	}

	/** Texto do primeiro filho Atom direto com esse nome (nao desce em entry/author). */
	private String text(Element parent, String name) {
		for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child instanceof Element element && ATOM.equals(element.getNamespaceURI())
					&& name.equals(element.getLocalName())) {
				return element.getTextContent();
			}
		}
		return null;
	}

	private String saveBlog(TestAuthHelper.AuthSession author, String title, String description) throws Exception {
		java.util.Map<String, Object> body = new java.util.HashMap<>();
		body.put("title", title);
		body.put("description", description);
		MvcResult saved = mockMvc.perform(put("/blogs/me")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "blog-feed-save-" + author.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(saved.getResponse().getContentAsString()).get("slug").asText();
	}

	private String publish(TestAuthHelper.AuthSession author, String title, String body, String format)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/blogs/me/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "blog-feed-post-" + title.hashCode() + "-" + author.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", title, "body", body, "format", format,
					"notifySubscribers", false))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
