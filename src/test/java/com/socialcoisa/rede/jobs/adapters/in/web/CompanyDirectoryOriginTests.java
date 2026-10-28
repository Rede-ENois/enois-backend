package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * O diretório de empresas diz de onde veio cada empresa — a tela de empresas tem duas abas
 * ("Empresas do Énóis" e "Plataformas externas") e separa por esse campo.
 *
 * O campo existia no Leitor (`CompanyDirectoryReader.CompanySummary.origem`, com as constantes
 * `ORIGEM_ENOIS`/`ORIGEM_EXTERNA` e o comentário "a aba do front separa Énóis × plataformas
 * externas"), mas o controller montava o corpo da resposta SEM ele. No front, a aba externa
 * filtra por `c.origem === "externa"` — e um campo ausente nunca é "externa" — então a aba
 * ficava **sempre vazia**, sem erro e sem aviso.
 *
 * Este teste trava a origem no corpo da resposta: a interna vem como "enois" e a externa (vaga
 * agregada de plataforma) vem como "externa".
 */
@SpringBootTest
@AutoConfigureMockMvc
class CompanyDirectoryOriginTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void oDiretorioDizAOrigemDeCadaEmpresa() throws Exception {
		Instant agora = Instant.now();
		TestDatabase.insertJob("job_dir_enois", "Vaga interna", "Empresa Interna LTDA", "Remoto",
			"FULL_TIME", "SENIOR", List.of("Java"), agora);
		TestDatabase.insertExternalJob("ext_dir_externa", "Vaga agregada", "Plataforma Externa SA",
			"Remoto", "https://plataforma.exemplo.test/vaga/1", agora);

		MvcResult resposta = mockMvc.perform(get("/jobs/companies"))
			.andExpect(status().isOk())
			.andReturn();

		JsonNode raiz = objectMapper.readTree(resposta.getResponse().getContentAsString());

		// A interna e a externa estão no diretorio, cada uma com a sua origem.
		JsonNode interna = null;
		JsonNode externa = null;
		for (JsonNode item : raiz) {
			if ("Empresa Interna LTDA".equals(item.path("name").asText())) {
				interna = item;
			}
			if ("Plataforma Externa SA".equals(item.path("name").asText())) {
				externa = item;
			}
		}

		assertThat(interna).as("a empresa interna entra no diretorio").isNotNull();
		assertThat(externa).as("a empresa externa entra no diretorio").isNotNull();
		assertThat(interna.path("origem").asText()).isEqualTo("enois");
		assertThat(externa.path("origem").asText()).isEqualTo("externa");
	}

	@Test
	void aOrigemTambemVemNoFiltroPorNome() throws Exception {
		Instant agora = Instant.now();
		TestDatabase.insertExternalJob("ext_dir_filtro", "Vaga filtrada", "Plataforma do Filtro SA",
			"Remoto", "https://plataforma.exemplo.test/vaga/2", agora);

		mockMvc.perform(get("/jobs/companies").param("query", "Plataforma do Filtro"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].name").value("Plataforma do Filtro SA"))
			.andExpect(jsonPath("$[0].origem").value("externa"));
	}
}
