package com.socialcoisa.rede.jobs.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Parâmetros da busca de vagas externas.
 *
 * Um `record` em vez de uma lista de argumentos porque são muitos filtros opcionais, e a ordem não
 * diz nada: quem chama nomeia o que quer, e o adaptador lê só o que existe.
 *
 * Ordenação (`sort`):
 * <ul>
 *   <li>{@link #SORT_RECENT} (padrão) — mais recentes primeiro, pela data de publicação quando a
 *       fonte informa e pela primeira importação quando não informa;</li>
 *   <li>{@link #SORT_SALARY} — maior faixa primeiro; vagas sem salário ficam no fim;</li>
 *   <li>{@link #SORT_RELEVANCE} — para busca textual: título que começa com o termo vem antes.</li>
 * </ul>
 *
 * `excludedPlatform` existe porque a listagem geral não mistura as vagas cadastradas pela própria
 * comunidade (elas têm aba própria). `platform` (singular) restringe a UM agregador.
 */
public record ExternalJobSearch(String query, String platform, String excludedPlatform, String level,
		String workModel, String employmentType, String city, String state, String country, BigDecimal salaryMin,
		Boolean remoteOnly, Instant publishedAfter, String sort, Instant before, int limit) {

	public static final String SORT_RECENT = "recent";
	public static final String SORT_SALARY = "salary";
	public static final String SORT_RELEVANCE = "relevance";

	/** Os mesmos valores aceitos pelo filtro de modelo de trabalho da vaga interna. */
	public static final List<String> WORK_MODELS = List.of("REMOTE", "HYBRID", "ONSITE");

	/** Nível aceito no filtro (o mesmo conjunto da vaga interna). */
	public static final List<String> LEVELS = List.of("JUNIOR", "MID", "SENIOR", "LEAD");

	public static ExternalJobSearch recent(String query, String platform, String excludedPlatform, int limit) {
		return new ExternalJobSearch(query, platform, excludedPlatform, null, null, null, null, null, null, null,
			null, null, SORT_RECENT, null, limit);
	}

	/** Cursor válido: a ordenação por data usa o instante; as demais não paginam por cursor. */
	public boolean cursorable() {
		return SORT_RECENT.equals(sort == null ? SORT_RECENT : sort);
	}
}