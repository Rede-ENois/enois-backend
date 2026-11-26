package com.socialcoisa.rede.portfolio.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Regras das peças: referência só para projeto do dono, link só https, mídia só se for do dono e
 * do tipo certo. Cada uma recusa com código próprio, em vez de gravar uma peça que a tela não
 * consegue mostrar.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PortfolioBlockRulesTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private Map<String, Object> body(String type) {
		Map<String, Object> body = new HashMap<>();
		body.put("type", type);
		return body;
	}

	@Test
	void pecaDeProjetoSoAceitaProjetoDoDono() throws Exception {
		var owner = auth.authenticate("pf_regra_projeto_owner");
		var other = auth.authenticate("pf_regra_projeto_other");

		String projectOfOther = objectMapper
			.readTree(mockMvc.perform(post("/me/projects")
				.header("X-Session-Token", other.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Projeto de outra pessoa", "stack",
					java.util.List.of()))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString())
			.get("id").asText();

		Map<String, Object> block = body("PROJECT");
		block.put("projectId", projectOfOther);

		mockMvc.perform(post("/me/portfolio/blocks")
			.header("X-Session-Token", owner.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(block)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PORTFOLIO_BLOCK_PROJECT_INVALID"));
	}

	@Test
	void linkDaPecaPrecisaSerHttps() throws Exception {
		var owner = auth.authenticate("pf_regra_link");
		Map<String, Object> block = body("LINK");
		block.put("linkUrl", "http://exemplo.com/perfil");

		mockMvc.perform(post("/me/portfolio/blocks")
			.header("X-Session-Token", owner.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(block)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PORTFOLIO_LINK_INVALID"));
	}

	@Test
	void midiaInexistenteERecusada() throws Exception {
		var owner = auth.authenticate("pf_regra_midia");
		Map<String, Object> block = body("MEDIA");
		block.put("mediaId", "med_nao_existe");

		mockMvc.perform(post("/me/portfolio/blocks")
			.header("X-Session-Token", owner.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(block)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PORTFOLIO_BLOCK_MEDIA_INVALID"));
	}

	@Test
	void textoLongoDemaisERecusado() throws Exception {
		var owner = auth.authenticate("pf_regra_texto");
		Map<String, Object> block = body("TEXT");
		block.put("text", "a".repeat(4001));

		mockMvc.perform(post("/me/portfolio/blocks")
			.header("X-Session-Token", owner.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(block)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PORTFOLIO_BLOCK_TEXT_TOO_LONG"));
	}

	@Test
	void pecaDeTextoVaziaERecusada() throws Exception {
		var owner = auth.authenticate("pf_regra_vazio");

		mockMvc.perform(post("/me/portfolio/blocks")
			.header("X-Session-Token", owner.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body("TEXT"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PORTFOLIO_BLOCK_CONTENT_REQUIRED"));
	}

	@Test
	void tipoDesconhecidoERecusado() throws Exception {
		var owner = auth.authenticate("pf_regra_tipo");

		mockMvc.perform(post("/me/portfolio/blocks")
			.header("X-Session-Token", owner.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body("VIDEO_360"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PORTFOLIO_BLOCK_TYPE_REQUIRED"));
	}
}
