package com.socialcoisa.rede.portfolio.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;

import tools.jackson.databind.ObjectMapper;

/**
 * Portfólio: o dono edita o rascunho, o público só vê o publicado, e o HTML do autor passa pela
 * mesma limpeza de página do resto do enois.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PortfolioControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private String usernameOf(AuthSession session) {
		return jdbc.queryForObject("select username from users where id = ?", String.class, session.userId());
	}

	private MvcResult saveDraft(AuthSession session, String html) throws Exception {
		return mockMvc.perform(put("/me/portfolio/draft")
			.header("X-Session-Token", session.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("html", html))))
			.andExpect(status().isOk())
			.andReturn();
	}

	private String createTextBlock(AuthSession session, String text) throws Exception {
		MvcResult result = mockMvc.perform(post("/me/portfolio/blocks")
			.header("X-Session-Token", session.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("type", "TEXT", "text", text))))
			.andExpect(status().isCreated())
			.andReturn();
		var blocks = objectMapper.readTree(result.getResponse().getContentAsString()).path("blocks");
		return blocks.get(blocks.size() - 1).path("id").asText();
	}

	@Test
	void oRascunhoNaoApareceParaOpublicoAtePublicar() throws Exception {
		AuthSession owner = auth.authenticate("pf_dono_rascunho");
		saveDraft(owner, "<h1>Meu portfolio</h1>");

		mockMvc.perform(get("/portfolio/" + usernameOf(owner)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PORTFOLIO_NOT_FOUND"));

		mockMvc.perform(post("/me/portfolio/publish").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(1));

		mockMvc.perform(get("/portfolio/" + usernameOf(owner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.html").value("<h1>Meu portfolio</h1>"));
	}

	@Test
	void editarDepoisDePublicarNaoMudaOQueOPublicoVe() throws Exception {
		AuthSession owner = auth.authenticate("pf_dono_edicao");
		saveDraft(owner, "<p>versao um</p>");
		mockMvc.perform(post("/me/portfolio/publish").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());

		saveDraft(owner, "<p>versao dois</p>");

		mockMvc.perform(get("/portfolio/" + usernameOf(owner)))
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.html").value("<p>versao um</p>"));

		mockMvc.perform(post("/me/portfolio/publish").header("X-Session-Token", owner.token()))
			.andExpect(jsonPath("$.version").value(2));

		mockMvc.perform(get("/portfolio/" + usernameOf(owner)))
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.html").value("<p>versao dois</p>"));
	}

	@Test
	void oHtmlDoAutorSaiLimpoNaGravacao() throws Exception {
		AuthSession owner = auth.authenticate("pf_dono_limpeza");
		MvcResult saved = saveDraft(owner, """
			<h2 style="color:red" onclick="alert(1)">Titulo</h2>
			<script>alert(document.cookie)</script>
			<iframe src="https://evil.example/x"></iframe>
			""");

		String html = objectMapper.readTree(saved.getResponse().getContentAsString()).path("html").asText();
		assertThat(html).contains("Titulo");
		assertThat(html).doesNotContain("<script", "evil.example", "onclick");
	}

	@Test
	void linkPerigosoNoHtmlERecusadoAntesDeGravar() throws Exception {
		AuthSession owner = auth.authenticate("pf_dono_link");

		mockMvc.perform(put("/me/portfolio/draft")
			.header("X-Session-Token", owner.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("html", "<a href=\"javascript:alert(1)\">x</a>"))))
			.andExpect(status().isBadRequest());

		mockMvc.perform(get("/me/portfolio").header("X-Session-Token", owner.token()))
			.andExpect(jsonPath("$.html").doesNotExist());
	}

	@Test
	void publicarPortfolioVazioERecusado() throws Exception {
		AuthSession owner = auth.authenticate("pf_dono_vazio");
		mockMvc.perform(post("/me/portfolio/publish").header("X-Session-Token", owner.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PORTFOLIO_EMPTY"));
	}

	@Test
	void asPecasEntramNoRascunhoESaemPublicadas() throws Exception {
		AuthSession owner = auth.authenticate("pf_dono_pecas");
		createTextBlock(owner, "primeira peca");

		mockMvc.perform(get("/me/portfolio").header("X-Session-Token", owner.token()))
			.andExpect(jsonPath("$.blocks[0].text").value("primeira peca"))
			.andExpect(jsonPath("$.blocks[0].published").value(false));

		mockMvc.perform(post("/me/portfolio/publish").header("X-Session-Token", owner.token()))
			.andExpect(jsonPath("$.blocks[0].text").value("primeira peca"));

		mockMvc.perform(get("/portfolio/" + usernameOf(owner)))
			.andExpect(jsonPath("$.blocks[0].text").value("primeira peca"));

		mockMvc.perform(get("/me/portfolio").header("X-Session-Token", owner.token()))
			.andExpect(jsonPath("$.blocks[0].published").value(true));
	}

	@Test
	void reordenarExigeTodasAsPecasUmaVez() throws Exception {
		AuthSession owner = auth.authenticate("pf_dono_ordem");
		String first = createTextBlock(owner, "primeira");
		String second = createTextBlock(owner, "segunda");

		mockMvc.perform(put("/me/portfolio/order").header("X-Session-Token", owner.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("order", List.of(first)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PORTFOLIO_ORDER_INVALID"));

		mockMvc.perform(put("/me/portfolio/order").header("X-Session-Token", owner.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("order", List.of(second, first)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.blocks[0].text").value("segunda"))
			.andExpect(jsonPath("$.blocks[1].text").value("primeira"));
	}

	@Test
	void semSessaoAoDonoERecusadaALeituraPublicaEAberta() throws Exception {
		AuthSession owner = auth.authenticate("pf_dono_sem_sessao");
		saveDraft(owner, "<p>publico</p>");
		mockMvc.perform(post("/me/portfolio/publish").header("X-Session-Token", owner.token()));

		mockMvc.perform(get("/portfolio/" + usernameOf(owner)))
			.andExpect(status().isOk());

		mockMvc.perform(put("/me/portfolio/draft")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("html", "<p>x</p>"))))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/me/portfolio"))
			.andExpect(status().isUnauthorized());
	}
}
