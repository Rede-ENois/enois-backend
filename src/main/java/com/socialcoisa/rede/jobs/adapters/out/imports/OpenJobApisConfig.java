package com.socialcoisa.rede.jobs.adapters.out.imports;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.socialcoisa.rede.jobs.application.port.out.JobImportSource;

import tools.jackson.databind.ObjectMapper;

/**
 * Fontes de vagas por API PUBLICA (sem chave) — agregadores que permitem listar e redirecionar para a
 * vaga original. Mapeamento declarado por fonte; nada de scraping.
 *
 * Atribuicao: Remotive e Jobicy exigem credito com link de volta. O credito e feito pelo nome da
 * plataforma (selo "Vaga externa · {plataforma}") + a URL da propria fonte na vaga.
 *
 * Os caminhos de metadado (`MetaPaths`) só são preenchidos quando a API realmente publica o campo —
 * caminho que não existe na resposta devolve nulo, e a vaga fica sem aquele dado.
 *
 * `defaultWorkModel` é o modelo que a PRÓPRIA fonte declara: as quatro são agregadores de vaga
 * remota, então toda vaga publicada nelas é remota por definição da fonte. Não é dedução a partir do
 * título — é o que o agregador é. Quando a fonte informa o modelo no item, o item vence.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rede.jobs.import.open-apis.enabled", matchIfMissing = true)
class OpenJobApisConfig {

	/** Remotive — vagas remotas; credito obrigatorio (ver docs/JOB_SOURCES.md). */
	@Bean
	JobImportSource remotiveJobSource(ObjectMapper objectMapper) {
		return new OpenJobApiSource(new OpenJobApiSource.Spec("Remotive",
			"https://remotive.com/api/remote-jobs?limit=200", false, "jobs", "id", "title", "company_name",
			"candidate_required_location", "salary", null, null, null, "job_type", "url", null,
			"publication_date", false, null,
			"Remotive (https://remotive.com) — credito obrigatorio.", "description", "REMOTE", true,
			// A API não separa cidade/estado/país: `candidate_required_location` é texto livre
			// ("Worldwide", "Brazil"), e vira `city` só quando é um lugar único.
			new OpenJobApiSource.MetaPaths(null, null, null, null, null, null, null)), objectMapper);
	}

	/** Jobicy — API v2 publica; credito + link de origem exigidos. */
	@Bean
	JobImportSource jobicyJobSource(ObjectMapper objectMapper) {
		return new OpenJobApiSource(new OpenJobApiSource.Spec("Jobicy",
			"https://jobicy.com/api/v2/remote-jobs?count=50", false, "jobs", "id", "jobTitle", "companyName",
			"jobGeo", null, "salaryMin", "salaryMax", "salaryCurrency", "jobType[0]", "url", null,
			"pubDate", false, null,
			"Jobicy (https://jobicy.com) — credito e link de origem obrigatorios.", "jobDescription", "REMOTE",
			true,
			// `jobLevel` é o nível declarado; `jobGeo` é a região aceita (não é cidade);
			// `salaryPeriod` diz se a faixa é anual, mensal ou por hora.
			new OpenJobApiSource.MetaPaths("jobLevel", null, null, null, null, "salaryPeriod", null)),
			objectMapper);
	}

	/** RemoteOK — JSON publico (primeiro item da lista e um aviso legal; ignorado). */
	@Bean
	JobImportSource remoteOkJobSource(ObjectMapper objectMapper) {
		return new OpenJobApiSource(new OpenJobApiSource.Spec("RemoteOK",
			"https://remoteok.com/api", true, null, "id", "position", "company", "location", null,
			"salary_min", "salary_max", null, null, "url", "apply_url", "date", false, null,
			"RemoteOK (https://remoteok.com) — credito obrigatorio.", "description", "REMOTE", true,
			// `location` do RemoteOK é a região aceita; `salary_min`/`max` são anuais em USD.
			new OpenJobApiSource.MetaPaths(null, null, null, null, null, null, null)), objectMapper);
	}

	/** Himalayas — API publica de vagas remotas. */
	@Bean
	JobImportSource himalayasJobSource(ObjectMapper objectMapper) {
		return new OpenJobApiSource(new OpenJobApiSource.Spec("Himalayas",
			"https://himalayas.app/jobs/api?limit=100", false, "jobs", "applicationLink", "title", "companyName",
			"locationRestrictions[0]", null, "minSalary", "maxSalary", "currency", "employmentType",
			"applicationLink", "applicationLink", "pubDate", true, null,
			"Himalayas (https://himalayas.app).", "description", "REMOTE", true,
			// `seniority` é uma lista ("Mid-level", "Senior"); `salaryPeriod` diz a base da faixa;
			// `expiryDate` é o prazo que a própria fonte declara — e os termos dela exigem remover
			// a vaga vencida, que é exatamente o que a expiração faz.
			new OpenJobApiSource.MetaPaths("seniority[0]", null, null, null, null, "salaryPeriod",
				"expiryDate")), objectMapper);
	}
}
