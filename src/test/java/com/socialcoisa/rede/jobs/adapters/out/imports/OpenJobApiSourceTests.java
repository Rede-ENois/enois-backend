package com.socialcoisa.rede.jobs.adapters.out.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.jobs.application.port.out.JobImportSource;

import tools.jackson.databind.ObjectMapper;

/**
 * Mapeamento das APIs publicas contra o JSON que elas realmente publicam (capturado em 2026-11-11).
 * Sem rede: o teste garante o mapeamento, nao a disponibilidade da fonte.
 *
 * As fontes vem da configuracao de PRODUCAO ({@link OpenJobApisConfig}), nao de uma copia: um caminho
 * de campo errado na config tem que aparecer aqui.
 */
class OpenJobApiSourceTests {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final OpenJobApisConfig config = new OpenJobApisConfig();

	/**
	 * Remotive publica `publication_date` SEM fuso ("2026-11-08T21:47:58"). Antes disso a data se
	 * perdia, e a vaga ficava sem publicacao — ordenada por ultimo e fora do filtro por data.
	 */
	@Test
	void remotiveDateWithoutOffsetBecomesUtc() {
		var job = parseOne(config.remotiveJobSource(objectMapper), REMOTIVE_JSON);

		assertThat(job.publishedAt()).isEqualTo(Instant.parse("2026-11-08T21:47:58Z"));
		assertThat(job.meta().workModel()).isEqualTo("REMOTE");
	}

	@Test
	void remotiveSalaryTextIsKeptVerbatim() {
		var job = parseOne(config.remotiveJobSource(objectMapper), REMOTIVE_JSON);

		assertThat(job.salary()).isEqualTo("OTE $25k - $35k");
		// A faixa numerica nao existe no formato da fonte: nada e adivinhado.
		assertThat(job.meta().salaryMinAmount()).isNull();
		assertThat(job.meta().salaryMaxAmount()).isNull();
	}

	/**
	 * Jobicy publica a faixa em `salaryMin`/`salaryMax` com a moeda em `salaryCurrency` e a base em
	 * `salaryPeriod`. O mapeamento antigo apontava para `annualSalaryMin`/`annualSalaryMax`, campos
	 * que a API v2 nao tem: toda vaga da fonte entrava sem salario e o filtro de piso salarial
	 * devolvia zero.
	 */
	@Test
	void jobicyReadsTheSalaryFieldNamesItActuallyPublishes() {
		var job = parseOne(config.jobicyJobSource(objectMapper), JOBICY_JSON);

		assertThat(job.meta().salaryMinAmount()).isEqualByComparingTo(new BigDecimal("215000"));
		assertThat(job.meta().salaryMaxAmount()).isEqualByComparingTo(new BigDecimal("240000"));
		assertThat(job.meta().salaryCurrency()).isEqualTo("USD");
		assertThat(job.meta().salaryPeriodCode()).isEqualTo("YEAR");
		assertThat(job.salary()).isEqualTo("USD 215000 - 240000");
		assertThat(job.employmentType()).isEqualTo("Full-Time");
	}

	/** `jobGeo` e a regiao aceita ("USA"), nao uma cidade — nem cidade, nem localidade de texto. */
	@Test
	void jobicyRegionIsNotStoredAsCity() {
		var job = parseOne(config.jobicyJobSource(objectMapper), JOBICY_JSON);

		assertThat(job.location()).isEqualTo("USA");
		assertThat(job.meta().city()).isNull();
	}

	/** Himalayas: a moeda esta em `currency`, o vinculo em `employmentType`, o nivel em `seniority[0]`. */
	@Test
	void himalayasReadsCurrencyEmploymentTypeAndSeniority() {
		var job = parseOne(config.himalayasJobSource(objectMapper), HIMALAYAS_JSON);

		assertThat(job.meta().salaryMinAmount()).isEqualByComparingTo(new BigDecimal("24"));
		assertThat(job.meta().salaryCurrency()).isEqualTo("USD");
		assertThat(job.meta().salaryPeriodCode()).isEqualTo("HOUR");
		assertThat(job.employmentType()).isEqualTo("Contractor");
		assertThat(job.meta().level()).isEqualTo("MID");
		assertThat(job.meta().workModel()).isEqualTo("REMOTE");
		// `expiryDate` e epoch em segundos, como `pubDate`.
		assertThat(job.meta().expiresAt()).isEqualTo(Instant.ofEpochSecond(1799586505));
	}

	/**
	 * Agregador de vaga remota publica vaga remota: quando o item nao diz o modelo, o modelo que a
	 * propria fonte declara vale. Sem isso, o filtro "Remoto" da aba externas devolvia zero vaga
	 * em todas as quatro fontes.
	 */
	@Test
	void remoteOnlyAggregatorFallbackAppliesButAnItemThatSaysOtherwiseWins() {
		var jobs = parseAll(config.remoteOkJobSource(objectMapper), AGGREGATOR_JSON);

		assertThat(jobs.get(0).meta().workModel()).isEqualTo("REMOTE");
		// A localidade que diz "Hybrid" fala mais alto que o padrao da fonte.
		assertThat(jobs.get(1).meta().workModel()).isEqualTo("HYBRID");
	}

	/** Vaga descartada pela fonte continua descartavel: nada aqui inventa titulo ou url. */
	@Test
	void itemsWithoutTitleOrUrlAreSkipped() {
		assertThat(parseAll(config.remoteOkJobSource(objectMapper), AGGREGATOR_JSON)).hasSize(2);
	}

	// ---------- fixtures ----------

	private JobImportSource.ExternalJob parseOne(JobImportSource source, String json) {
		var jobs = parseAll(source, json);
		assertThat(jobs).hasSize(1);
		return jobs.get(0);
	}

	private List<JobImportSource.ExternalJob> parseAll(JobImportSource source, String json) {
		// `discover` sai para a rede; o parse e o mesmo caminho, so sem o fetch.
		return ((OpenJobApiSource) source).parse(objectMapper.readTree(json), 100);
	}

	private static final String REMOTIVE_JSON = """
		{"jobs":[
			{"id":2086540,"url":"https://remotive.com/remote-jobs/sales/inside-sales-contractor-2086540",
			 "title":"Inside Sales Contractor","company_name":"Credit Wellness, LLC",
			 "category":"Sales","tags":["CRM"],"job_type":"full_time",
			 "publication_date":"2026-11-08T21:47:58",
			 "candidate_required_location":"Worldwide","salary":"OTE $25k - $35k",
			 "description":"<p>Inside sales.</p>"}
		]}""";

	private static final String JOBICY_JSON = """
		{"jobs":[
			{"id":161259,"url":"https://jobicy.com/jobs/161259-associate-general-counsel-3",
			 "jobTitle":"Associate General Counsel","companyName":"Pair Team",
			 "jobType":["Full-Time"],"jobGeo":"USA","jobLevel":"Director",
			 "jobDescription":"<p>Legal.</p>","pubDate":"2026-11-11T10:53:05+00:00",
			 "salaryMin":215000,"salaryMax":240000,"salaryCurrency":"USD","salaryPeriod":"yearly"}
		]}""";

	private static final String HIMALAYAS_JSON = """
		{"jobs":[
			{"title":"Linguistic AI Auditor - Malay (Singapore)","companyName":"RWS Group",
			 "employmentType":"Contractor","minSalary":24,"maxSalary":24,"salaryPeriod":"hourly",
			 "seniority":["Mid-level"],"currency":"USD","locationRestrictions":["Singapore"],
			 "description":"<p>Linguistics.</p>","pubDate":1794402505,"expiryDate":1799586505,
			 "applicationLink":"https://himalayas.app/companies/rws-group/jobs/linguistic-ai-auditor"}
		]}""";

	/** Formato do RemoteOK: `position` e o titulo, `location` e a regiao aceita. */
	private static final String AGGREGATOR_JSON = """
		[
			{"id":"1","position":"Backend Engineer","company":"Acme","location":"Worldwide",
			 "url":"https://example.test/jobs/1","date":"2026-11-10T10:00:00Z","description":"Java"},
			{"id":"2","position":"Product Designer","company":"Acme","location":"Hybrid - Lisbon",
			 "url":"https://example.test/jobs/2","date":"2026-11-10T11:00:00Z","description":"Figma"},
			{"id":"3","position":"","url":"https://example.test/jobs/3"},
			{"id":"4","position":"Sem URL"}
		]""";
}
