package com.socialcoisa.rede.jobs.adapters.out.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * Contrato das fontes novas contra JSON FIXO (capturado da API real). Sem rede: o teste garante o
 * mapeamento, não a disponibilidade da fonte.
 */
class ExternalProviderSourcesTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	// ---------- Arbeitnow ----------

	private static final String ARBEITNOW_JSON = """
		{"data":[
			{"slug":"senior-java-dev-berlin-455229","company_name":"Acme GmbH",
			 "title":"Senior Java Developer (m/w/d)","description":"<p>Java</p>",
			 "remote":true,"url":"https://www.arbeitnow.com/jobs/companies/acme/senior-java-dev-berlin-455229",
			 "tags":["Java","Spring"],"job_types":["berufserfahren","vollzeit"],
			 "location":"Berlin","created_at":1700000000},
			{"slug":"frontend-intern-munich-455230","company_name":"Beta AG",
			 "title":"Frontend Intern","description":"<p>React</p>",
			 "remote":false,"url":"https://www.arbeitnow.com/jobs/companies/beta/frontend-intern-munich-455230",
			 "tags":["React"],"job_types":["praktikum"],
			 "location":"München","created_at":1700000100},
			{"title":"Sem slug nem remoto","url":"https://www.arbeitnow.com/jobs/x","remote":false,
			 "location":"Hamburg","created_at":null}
		],
		"links":{"next":"https://www.arbeitnow.com/api/job-board-api?page=2"}}""";

	@Test
	void arbeitnowMapsRemoteLocationAndTaxonomy() {
		var source = new ArbeitnowJobSource("https://www.arbeitnow.com/api/job-board-api", true, 3, objectMapper);
		var jobs = source.parse(ARBEITNOW_JSON, 10);

		assertThat(jobs).hasSize(3);
		var senior = jobs.get(0);
		assertThat(senior.externalId()).isEqualTo("senior-java-dev-berlin-455229");
		assertThat(senior.company()).isEqualTo("Acme GmbH");
		assertThat(senior.location()).isEqualTo("Berlin");
		// O booleano da fonte vira o código do filtro; nada é adivinhado pelo título.
		assertThat(senior.meta().workModel()).isEqualTo("REMOTE");
		// "berufserfahren" (experiente) não é inequívoco: nível fica nulo.
		assertThat(senior.meta().level()).isNull();
		assertThat(senior.publishedAt()).isEqualTo(java.time.Instant.ofEpochSecond(1700000000));
		// A URL de atribuição é o próprio link da vaga (termos: "linking back").
		assertThat(senior.meta().attributionUrl()).contains("arbeitnow.com");

		var intern = jobs.get(1);
		assertThat(intern.meta().workModel()).isNull(); // presencial: sem booleano remoto
		assertThat(intern.meta().level()).isEqualTo("JUNIOR"); // "praktikum" = estágio
	}

	@Test
	void arbeitnowRespectsTheLimitAndDisabledFlag() {
		var source = new ArbeitnowJobSource("https://www.arbeitnow.com/api/job-board-api", true, 3, objectMapper);
		assertThat(source.parse(ARBEITNOW_JSON, 2)).hasSize(2);

		var off = new ArbeitnowJobSource("https://www.arbeitnow.com/api/job-board-api", false, 3, objectMapper);
		assertThat(off.available()).isFalse();
		assertThat(new ArbeitnowJobSource("https://www.arbeitnow.com/api/job-board-api", true, 3, objectMapper)
			.available()).isTrue();
	}

	// ---------- GitHub comunitárias ----------

	private static final String GITHUB_JSON = """
		[
			{"number":12494,"title":"[Remoto] Desenvolvedor(a) Backend Pleno - Mimix AI",
			 "html_url":"https://github.com/backend-br/vagas/issues/12494",
			 "created_at":"2026-11-09T12:03:33Z","updated_at":"2026-11-09T12:29:40Z",
			 "labels":[{"name":"PJ"},{"name":"Pleno"},{"name":"Remoto"},{"name":"verificada"}],
			 "body":"### Sobre a empresa\\n\\nA Mimix AI desenvolve uma plataforma.\\n\\n### Descrição da vaga\\n\\nBackend pleno.\\n\\n### Nível\\n\\nPleno\\n\\n### Regime de contratação\\n\\nPJ\\n\\n### Modelo de trabalho\\n\\nRemoto\\n\\n### Localização\\n\\nBrasil\\n\\n### Como se candidatar\\n\\nEnvie e-mail para vagas@mimix.ai"},
			{"number":12493,"title":"[Presencial] Estágio em QA - Acme",
			 "html_url":"https://github.com/backend-br/vagas/issues/12493",
			 "created_at":"2026-11-07T20:58:13Z",
			 "labels":[{"name":"CLT"},{"name":"Júnior"},{"name":"São Paulo"}],
			 "body":"### Localização\\n\\nSão Paulo - SP\\n\\n### Como se candidatar\\n\\nPelo site."},
			{"number":10,"title":"PR que não é vaga","html_url":"https://github.com/backend-br/vagas/pull/10",
			 "pull_request":{"url":"https://api.github.com/repos/backend-br/vagas/pulls/10"},
			 "body":"não é vaga"}
		]""";

	@Test
	void githubCommunityReadsTheSectionsLabelsAndSkipsPullRequests() {
		var source = new GitHubCommunityJobSource("backend-br/vagas", "GitHub · backend-br", true, 100, "",
			objectMapper);
		var jobs = source.parse(GITHUB_JSON, 10);

		// O pull request não é vaga.
		assertThat(jobs).hasSize(2);

		var pleno = jobs.get(0);
		assertThat(pleno.externalId()).isEqualTo("12494");
		// O colchete do título é modelo de trabalho, não parte do nome da vaga.
		assertThat(pleno.title()).isEqualTo("Desenvolvedor(a) Backend Pleno - Mimix AI");
		assertThat(pleno.url()).contains("issues/12494");
		// As seções fixas do template viram metadados; a taxonomia normaliza o texto.
		assertThat(pleno.meta().level()).isEqualTo("MID"); // "Pleno"
		assertThat(pleno.meta().workModel()).isEqualTo("REMOTE"); // "Remoto"
		assertThat(pleno.employmentType()).isEqualTo("PJ");
		assertThat(pleno.publishedAt()).isEqualTo(java.time.Instant.parse("2026-11-09T12:03:33Z"));
		// A atribuição é a própria issue (MIT; o crédito é o link de origem).
		assertThat(pleno.meta().attributionUrl()).isEqualTo(pleno.url());

		var estagio = jobs.get(1);
		assertThat(estagio.title()).isEqualTo("Estágio em QA - Acme");
		assertThat(estagio.meta().level()).isEqualTo("JUNIOR");
		assertThat(estagio.meta().workModel()).isEqualTo("ONSITE"); // "[Presencial]"
		assertThat(estagio.employmentType()).isEqualTo("CLT");
	}

	@Test
	void githubCommunityDisabledWithoutConfig() {
		assertThat(new GitHubCommunityJobSource("backend-br/vagas", "GitHub · backend-br", false, 100, "",
			objectMapper).available()).isFalse();
		assertThat(new GitHubCommunityJobSource("backend-br/vagas", "GitHub · backend-br", true, 100, "",
			objectMapper).available()).isTrue();
	}

	// ---------- Adzuna ----------

	private static final String ADZUNA_JSON = """
		{"results":[
			{"id":"4567890","title":"Desenvolvedor Java Sênior","description":"Java 21",
			 "redirect_url":"https://www.adzuna.com.br/jr-4567890",
			 "company":{"display_name":"Acme Brasil"},
			 "location":{"display_name":"São Paulo"},
			 "salary_min":8000.0,"salary_max":12000.0,
			 "contract_time":"permanent","contract_type":"full_time",
			 "created":"2026-11-08T10:00:00Z"}
		]}""";

	@Test
	void adzunaMapsSalaryRangeAndAttribution() {
		var source = new AdzunaJobSource("https://api.adzuna.com/v1/api/jobs", "br", "id", "key", true, 2,
			objectMapper);
		var jobs = source.parse(ADZUNA_JSON, 10);

		assertThat(jobs).hasSize(1);
		var job = jobs.get(0);
		assertThat(job.externalId()).isEqualTo("4567890");
		assertThat(job.company()).isEqualTo("Acme Brasil");
		assertThat(job.location()).isEqualTo("São Paulo");
		assertThat(job.meta().salaryMinAmount()).isEqualByComparingTo(new BigDecimal("8000.0"));
		assertThat(job.meta().salaryMaxAmount()).isEqualByComparingTo(new BigDecimal("12000.0"));
		// A atribuição é o link da Adzuna, não da vaga (termos: "The Adzuna API").
		assertThat(job.meta().attributionUrl()).isEqualTo("https://www.adzuna.com");
		assertThat(job.publishedAt()).isEqualTo(java.time.Instant.parse("2026-11-08T10:00:00Z"));
	}

	@Test
	void adzunaStaysUnavailableWithoutCredentialsEvenWhenEnabled() {
		// Sem app-id/key: disponibilidade falsa (o plano gratuito é de avaliação).
		assertThat(new AdzunaJobSource("https://api.adzuna.com/v1/api/jobs", "br", "", "", true, 2, objectMapper)
			.available()).isFalse();
		assertThat(new AdzunaJobSource("https://api.adzuna.com/v1/api/jobs", "br", "id", "", true, 2, objectMapper)
			.available()).isFalse();
		// Desligada por configuração, mesmo com credencial.
		assertThat(new AdzunaJobSource("https://api.adzuna.com/v1/api/jobs", "br", "id", "key", false, 2,
			objectMapper).available()).isFalse();
		// Com credencial e habilitada: disponível.
		assertThat(new AdzunaJobSource("https://api.adzuna.com/v1/api/jobs", "br", "id", "key", true, 2,
			objectMapper).available()).isTrue();
	}

	// ---------- Taxonomia ----------

	@Test
	void taxonomyTranslatesWithoutInventing() {
		assertThat(com.socialcoisa.rede.jobs.domain.JobTaxonomy.workModel("Remoto")).isEqualTo("REMOTE");
		assertThat(com.socialcoisa.rede.jobs.domain.JobTaxonomy.workModel("Remote")).isEqualTo("REMOTE");
		assertThat(com.socialcoisa.rede.jobs.domain.JobTaxonomy.workModel("100% Híbrido")).isEqualTo("HYBRID");
		assertThat(com.socialcoisa.rede.jobs.domain.JobTaxonomy.workModel("Presencial")).isEqualTo("ONSITE");
		assertThat(com.socialcoisa.rede.jobs.domain.JobTaxonomy.workModel("Worldwide")).isNull();

		assertThat(com.socialcoisa.rede.jobs.domain.JobTaxonomy.level("Pleno")).isEqualTo("MID");
		assertThat(com.socialcoisa.rede.jobs.domain.JobTaxonomy.level("Sênior")).isEqualTo("SENIOR");
		assertThat(com.socialcoisa.rede.jobs.domain.JobTaxonomy.level("Estágio")).isEqualTo("JUNIOR");
		assertThat(com.socialcoisa.rede.jobs.domain.JobTaxonomy.level("Júnior")).isEqualTo("JUNIOR");
		// Texto que não diz nada sobre nível fica nulo — não inferir pelo título da vaga.
		assertThat(com.socialcoisa.rede.jobs.domain.JobTaxonomy.level("berufserfahren")).isNull();

		assertThat(com.socialcoisa.rede.jobs.domain.JobTaxonomy.employmentType("PJ")).isEqualTo("PJ");
		assertThat(com.socialcoisa.rede.jobs.domain.JobTaxonomy.employmentType("CLT")).isEqualTo("CLT");
		assertThat(com.socialcoisa.rede.jobs.domain.JobTaxonomy.salaryPeriod("annual")).isEqualTo("YEAR");
	}
}
