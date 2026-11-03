package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Origem de conteudo: `GET /pages/{postId}` serve o HTML sanitizado da pagina.
 *
 * O isolamento de verdade e a ORIGEM separada (registro de DNS + rota do gateway) e o sandbox do
 * quadro; o que estes testes provam, no CI, e a parte que da para provar sem navegador:
 *  - a politica vai no CABECALHO (nao dentro do documento, onde o conteudo poderia mexer);
 *  - `script-src 'none'`: script na pagina nao roda nem que a sanitizacao falhasse;
 *  - `frame-ancestors`: so o app embute a pagina;
 *  - o corpo servido e o HTML JA sanitizado (nenhum `<script>` saiu da gravacao);
 *  - so pagina publicada e ativa responde; o resto e 404 (nao vaza rascunho nem post apagado);
 *  - responde sem sessao (os posts sao publicos).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PageContentTests {

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
	void servesSanitizedPageWithThePolicyInHeaders() throws Exception {
		var author = auth.authenticate("user_page_content1");
		String postId = publish(author.token(), "page-content-1", "PAGE",
			"<h1>Minha pagina</h1><script>alert(1)</script><p onclick=\"x()\">corpo</p>");

		MvcResult result = mockMvc.perform(get("/pages/" + postId))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/html")))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Referrer-Policy", "no-referrer"))
			.andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'none'")))
			.andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors")))
			.andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-src")))
			.andReturn();

		String body = result.getResponse().getContentAsString();
		assertThat(body).contains("Minha pagina").contains("corpo");
		assertThat(body).doesNotContain("<script").doesNotContain("onclick").doesNotContain("alert(");
	}

	/** Rascunho de outro tipo, post apagado e pagina sem HTML nao viram documento publico. */
	@Test
	void onlyPublishedPagesAreServed() throws Exception {
		var author = auth.authenticate("user_page_content2");
		String insight = publish(author.token(), "page-content-2", "INSIGHT", "Post comum, com texto.");
		String page = publish(author.token(), "page-content-3", "PAGE", "<p>pagina que sera apagada</p>");

		mockMvc.perform(get("/pages/" + insight)).andExpect(status().isNotFound());
		mockMvc.perform(get("/pages/post_inexistente_123")).andExpect(status().isNotFound());

		mockMvc.perform(delete("/feed/posts/" + page)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "page-content-del"))
			.andExpect(status().isOk());
		mockMvc.perform(get("/pages/" + page)).andExpect(status().isNotFound());
	}

	private String publish(String token, String key, String type, String html) throws Exception {
		Map<String, Object> body = new HashMap<>(Map.of("topic", "TECNOLOGIA", "type", type, "content", "texto", "tags", List.of(),
			"mediaIds", List.of()));
		if ("PAGE".equals(type)) {
			body.put("pageHtml", html);
		}
		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	/**
	 * Cartao da miniatura: para uma PAGINA, o que aparece e o TEXTO dela — nao a imagem de marca.
	 * O teste confere o tipo/assinatura do PNG e que ele nao sai vazio; com
	 * `-DpageCard.dump=true` ele grava o arquivo, que foi como eu olhei o resultado.
	 */
	@Test
	void previewCardShowsThePageText() throws Exception {
		var author = auth.authenticate("user_page_card");
		String postId = publish(author.token(), "page-card-1", "PAGE",
			"<style>.ruido{color:red}</style><h1>Meu portfolio de dados</h1>"
				+ "<p>Sou analista, trabalho com pipelines de dados e escrevo sobre isso.</p>");

		MvcResult result = mockMvc.perform(get("/pages/" + postId + "/og.png"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/png"))
			.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age")))
			.andReturn();

		byte[] png = result.getResponse().getContentAsByteArray();
		assertThat(png.length).isGreaterThan(5_000);
		assertThat(png[0]).isEqualTo((byte) 0x89);
		assertThat(new String(png, 1, 3, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("PNG");

		if (Boolean.getBoolean("pageCard.dump")) {
			java.nio.file.Files.write(java.nio.file.Path.of("/tmp/page-card.png"), png);
		}
	}

	@Test
	void previewCardForSomethingThatIsNotAPageIsNotFound() throws Exception {
		var author = auth.authenticate("user_page_card2");
		String insight = publish(author.token(), "page-card-2", "INSIGHT", "Post comum.");

		mockMvc.perform(get("/pages/" + insight + "/og.png")).andExpect(status().isNotFound());
		mockMvc.perform(get("/pages/post_inexistente_999/og.png")).andExpect(status().isNotFound());
	}
}
