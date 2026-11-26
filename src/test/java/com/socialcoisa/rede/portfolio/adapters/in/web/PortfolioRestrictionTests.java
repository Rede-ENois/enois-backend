package com.socialcoisa.rede.portfolio.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Conta restrita pela moderação não edita nem publica o portfólio, e o portfólio de conta banida
 * some do perfil público. O portfólio é uma vitrine profissional, e restrição também impede
 * publicar nela (mesmo portão de escrita do feed, do blog e dos grupos).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PortfolioRestrictionTests {

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

	@Test
	void contaRestritaNaoEditaNemPublica() throws Exception {
		var owner = auth.authenticate("pf_restrito");
		jdbc.update("""
			insert into user_sanctions (id, user_id, type, reason, status, created_at)
			values (?, ?, 'RESTRICTION', 'Restricao de teste da moderação.', 'ACTIVE', now())
			""", "san_pf_restrito", owner.userId());

		mockMvc.perform(put("/me/portfolio/draft")
			.header("X-Session-Token", owner.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("html", "<p>tentativa</p>"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));

		mockMvc.perform(post("/me/portfolio/publish").header("X-Session-Token", owner.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));
	}

	@Test
	void portfolioDeContaBanidaNaoAparece() throws Exception {
		var owner = auth.authenticate("pf_banido");
		mockMvc.perform(put("/me/portfolio/draft")
			.header("X-Session-Token", owner.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("html", "<p>publico</p>"))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/me/portfolio/publish").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());

		String username = jdbc.queryForObject("select username from users where id = ?", String.class,
			owner.userId());
		jdbc.update("""
			insert into user_sanctions (id, user_id, type, reason, status, created_at)
			values (?, ?, 'BAN', 'Banimento de teste da moderação.', 'ACTIVE', now())
			""", "san_pf_banido", owner.userId());

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.get("/portfolio/" + username))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PORTFOLIO_NOT_FOUND"));
	}
}
