package com.socialcoisa.rede.releasenotes.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

/**
 * A rota interna das "Novidades" num ambiente SEM o token de deploy configurado — o estado em que
 * qualquer ambiente que ainda nao recebeu o secret fica.
 *
 * Fail-closed: sem `REDE_RELEASE_NOTES_DEPLOY_TOKEN` a rota responde 503 com codigo proprio e
 * nada entra no ar. Aceitar chamada sem credencial, ou publicar sem conferir, seria pior do que
 * nao ter a automacao.
 */
@SpringBootTest(properties = "rede.release-notes.deploy-token=")
@AutoConfigureMockMvc
class ReleaseNoteDeployNotConfiguredTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from release_note");
		jdbc.update("delete from deploy_publication");
	}

	@Test
	void semTokenConfiguradoARotaResponde503ENaoPublica() throws Exception {
		for (String token : java.util.Arrays.asList(null, "", "qualquer-coisa")) {
			var request = post("/internal/novidades-deploy")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("commitSha", "e".repeat(40), "items", List.of(Map.of("title", "T", "description", "D")))));
			if (token != null) {
				request = request.header("X-Deploy-Token", token);
			}
			mockMvc.perform(request)
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("DEPLOY_PUBLISH_NOT_CONFIGURED"));
		}

		// Nada publicado e nenhum deploy registrado: a rota desligada nao deixa rastro.
		mockMvc.perform(get("/novidades/atual")).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select count(*) from deploy_publication", Integer.class)).isZero();
	}

	@Test
	void leituraInternaTambemSobe503SemToken() throws Exception {
		mockMvc.perform(get("/internal/novidades-deploy/ultimo").header("X-Deploy-Token", "qualquer-coisa"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("DEPLOY_PUBLISH_NOT_CONFIGURED"));
	}
}