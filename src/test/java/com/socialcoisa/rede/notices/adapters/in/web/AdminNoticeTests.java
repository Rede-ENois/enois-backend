package com.socialcoisa.rede.notices.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * O aviso editavel por administradores: publico le, admin escreve, e o HTML passa pelo crivo de
 * sanitizacao antes de chegar na tela — o editor do painel nao e fonte confiavel.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminNoticeTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@DynamicPropertySource
	static void adminAllowlist(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "notice_admin@test.com");
	}

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from admin_notice");
	}

	@Test
	void semAvisoOFrontRecebeVazioEOLeidoSemSessao() throws Exception {
		mockMvc.perform(get("/avisos/ativo")).andExpect(status().isNoContent());
	}

	@Test
	void adminPublicaEQualquerVisitanteLe() throws Exception {
		var admin = auth.authenticate("notice_admin");

		mockMvc.perform(put("/admin/avisos")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Manutencao no fim da semana", "<p>O mercado fecha sabado.</p>", "both")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Manutencao no fim da semana"))
			.andExpect(jsonPath("$.surface").value("BOTH"))
			.andExpect(jsonPath("$.id").value("ativo"));

		// Leitura sem sessao: o aviso e da plataforma, nao do usuario logado.
		mockMvc.perform(get("/avisos/ativo"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.bodyHtml").value("<p>O mercado fecha sabado.</p>"))
			.andExpect(jsonPath("$.publishedAt").isNotEmpty())
			.andExpect(jsonPath("$.updatedAt").isNotEmpty());
	}

	@Test
	void contaComumNaoPublicaNemTiraDoAr() throws Exception {
		var pessoa = auth.authenticate("notice_plain_pessoa");

		mockMvc.perform(put("/admin/avisos")
				.header("X-Session-Token", pessoa.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Titulo", "<p>corpo</p>", "banner")))
			.andExpect(status().isForbidden());

		mockMvc.perform(delete("/admin/avisos").header("X-Session-Token", pessoa.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void oHtmlDoPainelSaiSanitizado() throws Exception {
		var admin = auth.authenticate("notice_admin");
		String perigoso = "<p>aviso</p><script>alert(1)</script>";

		mockMvc.perform(put("/admin/avisos")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Aviso limpo", perigoso, "modal")))
			.andExpect(status().isOk());

		String salvo = mockMvc.perform(get("/avisos/ativo"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(salvo).doesNotContain("<script");
	}

	@Test
	void linkPerigosoERecusadoNaBorda() throws Exception {
		var admin = auth.authenticate("notice_admin");

		mockMvc.perform(put("/admin/avisos")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Aviso limpo", "<p>aviso</p><a href=\"javascript:alert(1)\">clique</a>", "modal")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UNSAFE_LINK"));
	}

	@Test
	void reeditarSubstituiEMantemAPrimeiraPublicacao() throws Exception {
		var admin = auth.authenticate("notice_admin");

		mockMvc.perform(put("/admin/avisos")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Aviso", "<p>versao 1</p>", "banner")))
			.andExpect(status().isOk());

		// publishedAt lido do banco (nao da resposta do POST) para comparar com o que a segunda
		// edicao devolve: em MySQL o timestamp tem precisao de segundo e as duas origens diferem.
		String publicadaEm = mockMvc.perform(get("/avisos/ativo"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		String segunda = mockMvc.perform(put("/admin/avisos")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Aviso corrigido", "<p>versao 2</p>", "banner")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		// publishedAt preservado diz que a plataforma continua no mesmo aviso. A linha unica e a
		// garantia de que reeditar nao deixou dois avisos publicados ao mesmo tempo.
		assertThat(segunda).contains(field(publicadaEm, "publishedAt"));
		assertThat(segunda).contains("versao 2").doesNotContain("versao 1");
		assertThat(jdbc.queryForObject("select count(*) from admin_notice", Integer.class)).isEqualTo(1);
	}

	@Test
	void retirarOTirarDoArEIdempotente() throws Exception {
		var admin = auth.authenticate("notice_admin");
		mockMvc.perform(put("/admin/avisos")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Aviso", "<p>no ar</p>", "both")))
			.andExpect(status().isOk());

		mockMvc.perform(delete("/admin/avisos").header("X-Session-Token", admin.token()))
			.andExpect(status().isNoContent());
		mockMvc.perform(get("/avisos/ativo")).andExpect(status().isNoContent());
		mockMvc.perform(delete("/admin/avisos").header("X-Session-Token", admin.token()))
			.andExpect(status().isNoContent());
	}

	@Test
	void tituloVazioOSuperficieDesconhecidaErramCom400() throws Exception {
		var admin = auth.authenticate("notice_admin");

		mockMvc.perform(put("/admin/avisos")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("   ", "<p>corpo</p>", "banner")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_NOTICE_TITLE"));

		mockMvc.perform(put("/admin/avisos")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Aviso", "<p>corpo</p>", "lateral")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_NOTICE_SURFACE"));

		// Sem superficie o admin nao publica "sem querer" um banner que a tela transformaria em modal.
		mockMvc.perform(put("/admin/avisos")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Aviso", "bodyHtml", "<p>corpo</p>"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_NOTICE_SURFACE"));
	}

	private String body(String title, String html, String surface) throws Exception {
		return objectMapper.writeValueAsString(Map.of("title", title, "bodyHtml", html, "surface", surface));
	}

	private String field(String json, String name) {
		try {
			return objectMapper.readTree(json).get(name).asString();
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
