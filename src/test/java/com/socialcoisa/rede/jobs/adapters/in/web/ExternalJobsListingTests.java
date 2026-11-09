package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.jobs.application.port.out.ExternalJobRepository;
import com.socialcoisa.rede.jobs.domain.ExternalJob;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A listagem publica traz as vagas externas (da tabela external_jobs) DEPOIS das internas, marcadas
 * como EXTERNAL e com a URL da origem (para "Visitar vaga"). Interna e externa nao se misturam.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
	"rede.jobs.import.external-listing-enabled=true",
	"rede.jobs.import.jobradar.enabled=false",
	"rede.jobs.import.external-refresh-enabled=false"
})
class ExternalJobsListingTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ExternalJobRepository externalJobs;

	@Test
	void externalJobHasPublicDetailWithItsOriginalUrl() throws Exception {
		String id = "ext_detail_" + System.nanoTime();
		externalJobs.save(new ExternalJob(id, "Teste", id, "Vaga compartilhada",
			null, null, null, null, "https://exemplo.test/vaga/detalhe", Instant.now(), Instant.now(),
			ExternalJob.FOUND, null, null));

		MvcResult result = mockMvc.perform(get("/jobs/{id}", id))
			.andExpect(status().isOk()).andReturn();
		JsonNode job = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(job.path("id").asString()).isEqualTo(id);
		assertThat(job.path("title").asString()).isEqualTo("Vaga compartilhada");
		assertThat(job.path("sourceType").asString()).isEqualTo("EXTERNAL");
		assertThat(job.path("sourceUrl").asString()).isEqualTo("https://exemplo.test/vaga/detalhe");
	}

	@Test
	void searchFindsExternalJobsByCompany() throws Exception {
		// O diretorio de empresas e feito das empresas das vagas EXTERNAS: clicar num card leva a
		// busca pelo NOME DA EMPRESA. Enquanto a busca externa so olhava o titulo, esse caminho
		// devolvia lista vazia ("Monzo - 8 vagas" -> nenhuma vaga).
		externalJobs.save(new ExternalJob("ext_company_monzo", "BuscaEmpresa", "monzo-1",
			"Backend Engineer", "Monzo", "Remoto", null, null, "https://exemplo.test/vaga/monzo", Instant.now(),
			Instant.now(), ExternalJob.FOUND, null, null));
		externalJobs.save(new ExternalJob("ext_company_outra", "BuscaEmpresa", "outra-1",
			"Designer", "Outra Empresa", "Remoto", null, null, "https://exemplo.test/vaga/outra", Instant.now(),
			Instant.now(), ExternalJob.FOUND, null, null));

		MvcResult result = mockMvc.perform(get("/jobs").param("query", "Monzo").param("limit", "50"))
			.andExpect(status().isOk()).andReturn();
		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");

		boolean found = false;
		for (int i = 0; i < items.size(); i++) {
			JsonNode item = items.get(i);
			if ("EXTERNAL".equals(item.path("sourceType").asString())) {
				assertThat(item.path("company").asString()).containsIgnoringCase("monzo");
				found = true;
			}
		}
		assertThat(found).as("a busca pelo nome da empresa encontra a vaga externa dela").isTrue();
	}

	@Test
	void unknownExternalJobStillReturnsNotFound() throws Exception {
		mockMvc.perform(get("/jobs/ext_missing_" + System.nanoTime()))
			.andExpect(status().isNotFound());
	}

	@Test
	void searchFiltersExternalJobsByTitle() throws Exception {
		externalJobs.save(new ExternalJob("ext_search_java", "BuscaExterna", "java-1", "Desenvolvedor Java Senior",
			null, null, null, null, "https://exemplo.test/vaga/java", Instant.now(), Instant.now(),
			ExternalJob.FOUND, null, null));
		externalJobs.save(new ExternalJob("ext_search_design", "BuscaExterna", "design-1", "Designer de Produto",
			null, null, null, null, "https://exemplo.test/vaga/design", Instant.now(), Instant.now(),
			ExternalJob.FOUND, null, null));

		MvcResult result = mockMvc.perform(get("/jobs").param("query", "java").param("limit", "50"))
			.andExpect(status().isOk()).andReturn();
		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");

		boolean javaFound = false;
		for (int i = 0; i < items.size(); i++) {
			JsonNode item = items.get(i);
			String title = item.path("title").asString();
			if ("EXTERNAL".equals(item.path("sourceType").asString())) {
				// So a externa que casa com "java" pode aparecer.
				assertThat(title).containsIgnoringCase("java");
				javaFound = true;
			}
		}
		assertThat(javaFound).isTrue();
	}

	@Test
	void structuralFilterExcludesExternalJobs() throws Exception {
		String id = "ext_structural_" + System.nanoTime();
		externalJobs.save(new ExternalJob(id, "BuscaExterna", "s-1", "Vaga Externa Sem Tipo",
			null, null, null, null, "https://exemplo.test/vaga/s", Instant.now(), Instant.now(),
			ExternalJob.FOUND, null, null));

		MvcResult result = mockMvc.perform(get("/jobs").param("type", "FULL_TIME").param("limit", "50"))
			.andExpect(status().isOk()).andReturn();
		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
		for (int i = 0; i < items.size(); i++) {
			// Externas nao tem tipo: nao podem aparecer quando o filtro exige um tipo.
			assertThat(items.get(i).path("sourceType").asString()).isNotEqualTo("EXTERNAL");
		}
	}

	@Test
	void externalJobsComeAfterInternalOnes() throws Exception {
		String internalId = "job_ext_listing_internal";
		// A listagem ordena por posted_at desc e o banco de teste e compartilhado: outros testes
		// criam vagas "agora" que, com posted_at passado, empurrariam esta para fora do limit=20
		// e o índice sairia -1 no CI. Timestamp futuro garante o primeiro lugar sem depender
		// do volume do banco (mesma técnica de JobSearchContractTests).
		TestDatabase.insertJob(internalId, "Vaga Interna", "Coiso", "Remoto", "FULL_TIME", "MID",
			List.of("Java"), Instant.now().plusSeconds(3600));

		externalJobs.save(new ExternalJob("ext_listing_1", "ListagemExterna", "ext-1", "Vaga Externa de Teste",
			null, null, null, null, "https://exemplo.test/vaga/1", Instant.now(), Instant.now(),
			ExternalJob.FOUND, null, null));

		MvcResult result = mockMvc.perform(get("/jobs").param("limit", "20"))
			.andExpect(status().isOk()).andReturn();
		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");

		int internalIndex = -1;
		int firstExternalIndex = -1;
		java.util.Set<String> externalUrls = new java.util.HashSet<>();
		for (int i = 0; i < items.size(); i++) {
			JsonNode item = items.get(i);
			if (internalId.equals(item.path("id").asString())) internalIndex = i;
			if ("EXTERNAL".equals(item.path("sourceType").asString())) {
				if (firstExternalIndex < 0) firstExternalIndex = i;
				externalUrls.add(item.path("sourceUrl").asString());
			}
		}
		assertThat(internalIndex).isGreaterThanOrEqualTo(0);
		assertThat(firstExternalIndex).isGreaterThanOrEqualTo(0);
		// Internas primeiro: o bloco de externas comeca depois da interna.
		assertThat(internalIndex).isLessThan(firstExternalIndex);
		// O banco de teste e compartilhado: valida a URL da vaga deste teste, nao "a primeira".
		assertThat(externalUrls).contains("https://exemplo.test/vaga/1");
	}
}
