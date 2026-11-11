package com.socialcoisa.rede.jobs.application.port.out;

import java.util.List;

/**
 * Fonte externa de vagas. O modulo de vagas nao conhece fornecedor: cada plataforma implementa esta
 * porta (ex.: JobRadar) e e ligada por configuracao. Importacao so por API/feed oficial — nunca por
 * scraping de SPA nem pela API privada do fornecedor.
 */
public interface JobImportSource {

	/** Nome exibido da plataforma (ex.: "JobRadar"). */
	String name();

	/** Se a fonte esta realmente configurada (credencial presente). Sem isso, fica indisponivel. */
	boolean available();

	/** Busca vagas publicas na fonte. `limit` e um teto de itens por chamada. */
	List<ExternalJob> discover(int limit);

	/**
	 * Vaga normalizada para o modelo interno (nada inventado: campo ausente fica nulo).
	 *
	 * `meta` carrega o que a fonte informa de estruturado (senioridade, modelo de trabalho, faixa
	 * numérica, atribuição, expiração). Fica num record à parte para que uma fonte que só tem
	 * título/URL não precise preencher dez campos vazios.
	 */
	record ExternalJob(String externalId, String title, String company, String location, String description,
			String salary, String employmentType, String url, String applyUrl, String platform,
			java.time.Instant publishedAt, Meta meta) {

		/** Construtor das fontes que só entregam o básico (sem metadados estruturados). */
		public ExternalJob(String externalId, String title, String company, String location, String description,
				String salary, String employmentType, String url, String applyUrl, String platform,
				java.time.Instant publishedAt) {
			this(externalId, title, company, location, description, salary, employmentType, url, applyUrl,
				platform, publishedAt, Meta.empty());
		}
	}

	/**
	 * Metadados estruturados de uma vaga externa. Todos opcionais: nulo significa "a fonte não
	 * informou", nunca "não se aplica" — e nada aqui é inferido.
	 */
	record Meta(String level, String workModel, String city, String state, String country,
			java.math.BigDecimal salaryMinAmount, java.math.BigDecimal salaryMaxAmount, String salaryCurrency,
			String salaryPeriodCode, String attributionUrl, java.time.Instant expiresAt) {

		public static Meta empty() {
			return new Meta(null, null, null, null, null, null, null, null, null, null, null);
		}
	}
}
