package com.socialcoisa.rede.jobs.domain;

import java.time.Instant;

/**
 * Vaga de plataforma externa (ex.: JobRadar) ou cadastrada por alguem da comunidade. Nao e uma vaga
 * criada na plataforma: fica em tabela propria e so e LISTADA no enois, com a candidatura na origem.
 * O que a fonte nao fornece fica nulo (empresa/local/salario) — nada e inventado.
 *
 * `submittedBy` distingue as duas origens: nulo = importada de agregador; preenchido = cadastrada
 * a mao por essa pessoa (que pode excluir, e cuja linha a importacao nunca sobrescreve).
 *
 * `details` guarda o que a fonte informa de estruturado (publicacao, senioridade, modelo de
 * trabalho, faixa numerica, atribuicao e ciclo de vida). Ver {@link ExternalJobDetails}.
 */
public record ExternalJob(String id, String platform, String externalId, String title, String company,
		String location, String salary, String currency, String url, Instant importedAt, Instant lastCheckedAt,
		String status, String canonicalUrl, String applyEmail, String submittedBy, String description,
		String salaryMin, String salaryMax, String salaryPeriod, boolean salaryNotInformed,
		ExternalJobDetails details) {

	public static final String FOUND = "FOUND";
	public static final String POSSIBLY_CLOSED = "POSSIBLY_CLOSED";
	public static final String REMOVED = "REMOVED";

	/** Compatibilidade com os cadastros/queries que ainda nao informam os detalhes. */
	public ExternalJob(String id, String platform, String externalId, String title, String company, String location,
			String salary, String currency, String url, Instant importedAt, Instant lastCheckedAt, String status,
			String canonicalUrl, String applyEmail, String submittedBy) {
		this(id, platform, externalId, title, company, location, salary, currency, url, importedAt, lastCheckedAt,
			status, canonicalUrl, applyEmail, submittedBy, null, null, null, null, false, ExternalJobDetails.empty());
	}

	/** Construtor das importadas: so as da comunidade tem autor. */
	public ExternalJob(String id, String platform, String externalId, String title, String company, String location,
			String salary, String currency, String url, Instant importedAt, Instant lastCheckedAt, String status,
			String canonicalUrl, String applyEmail) {
		this(id, platform, externalId, title, company, location, salary, currency, url, importedAt, lastCheckedAt,
			status, canonicalUrl, applyEmail, null, null, null, null, null, false, ExternalJobDetails.empty());
	}

	/** Compatibilidade com os chamadores que ja informam os detalhes de texto, mas nao os estruturados. */
	public ExternalJob(String id, String platform, String externalId, String title, String company, String location,
			String salary, String currency, String url, Instant importedAt, Instant lastCheckedAt, String status,
			String canonicalUrl, String applyEmail, String submittedBy, String description, String salaryMin,
			String salaryMax, String salaryPeriod, boolean salaryNotInformed) {
		this(id, platform, externalId, title, company, location, salary, currency, url, importedAt, lastCheckedAt,
			status, canonicalUrl, applyEmail, submittedBy, description, salaryMin, salaryMax, salaryPeriod,
			salaryNotInformed, ExternalJobDetails.empty());
	}

	/** Vaga cadastrada por alguem da comunidade (e nao importada de agregador). */
	public boolean isCommunitySubmission() {
		return submittedBy != null && !submittedBy.isBlank();
	}

	/** A vaga aparece na busca ativa? Removida ou vencida nao aparece. */
	public boolean active(Instant now) {
		return details != null && details.active(now);
	}

	private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern
		.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

	/**
	 * Primeiro e-mail de candidatura que a descricao do anuncio publica (nulo quando nao ha).
	 *
	 * A descricao vem em HTML: as tags saem antes da busca, senao um endereco quebrado por marcacao
	 * passaria batido. Endereco de disparo automatico (noreply) e de exemplo nao entram — nao sao
	 * caixa de candidatura e so sujam o botao.
	 */
	public static String extractApplyEmail(String description) {
		if (description == null || description.isBlank()) {
			return null;
		}
		String text = description.replaceAll("<[^>]+>", " ");
		java.util.regex.Matcher matcher = EMAIL.matcher(text);
		while (matcher.find()) {
			String candidate = matcher.group().toLowerCase(java.util.Locale.ROOT);
			// Endereco que continua em caminho (`banner@empresa.com/logo.png`) e asset, nao caixa.
			boolean pathAfter = matcher.end() < text.length() && text.charAt(matcher.end()) == '/';
			if (pathAfter || candidate.startsWith("noreply@") || candidate.startsWith("no-reply@")
					|| candidate.contains("@example.")) {
				continue;
			}
			return candidate.length() > 320 ? candidate.substring(0, 320) : candidate;
		}
		return null;
	}

	/** Chave de deduplicacao entre fontes: a URL normalizada (ver UrlCanonicalizer). */
	public String dedupeKey() {
		return canonicalUrl != null && !canonicalUrl.isBlank() ? canonicalUrl : url;
	}
}
