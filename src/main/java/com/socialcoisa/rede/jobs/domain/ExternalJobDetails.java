package com.socialcoisa.rede.jobs.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * O que a fonte externa informa de ESTRUTURADO sobre a vaga, além do texto livre.
 *
 * Fica num record próprio porque são muitos campos opcionais e a vaga em si já tem vinte: passar
 * tudo posicionalmente num construtor só convidaria a trocar dois campos de lugar sem o compilador
 * reclamar. Campo nulo significa "a fonte não informou" — nada aqui é deduzido.
 *
 * @param publishedAt data de publicação na fonte (nula quando a fonte não publica)
 * @param employmentType vínculo como a fonte escreve ("FullTime", "PJ", "CLT"…), sem tradução
 * @param level senioridade normalizada (JUNIOR, MID, SENIOR, LEAD) ou nula
 * @param workModel REMOTE, HYBRID ou ONSITE — nulo quando a fonte não diz
 * @param city cidade, quando a fonte separa a localização
 * @param state estado/UF, quando a fonte separa a localização
 * @param country país, quando a fonte separa a localização
 * @param salaryMinAmount piso da faixa em número (para filtrar/ordenar), nulo se ilegível
 * @param salaryMaxAmount teto da faixa em número, nulo se ilegível
 * @param salaryCurrency moeda da faixa (BRL, USD, EUR…)
 * @param salaryPeriodCode HOUR, DAY, MONTH ou YEAR
 * @param attributionUrl link de volta para a fonte, exigido pelos termos de vários agregadores
 * @param lastSeenAt última vez que a fonte devolveu esta vaga
 * @param expiresAt prazo declarado pela fonte (nulo quando ela não declara)
 * @param removedAt quando a vaga saiu da listagem ativa (nula enquanto ativa)
 */
public record ExternalJobDetails(Instant publishedAt, String employmentType, String level, String workModel,
		String city, String state, String country, BigDecimal salaryMinAmount, BigDecimal salaryMaxAmount,
		String salaryCurrency, String salaryPeriodCode, String attributionUrl, Instant lastSeenAt,
		Instant expiresAt, Instant removedAt) {

	/** Vaga sem nenhum detalhe estruturado (cadastro da comunidade, fontes que só dão título e link). */
	public static ExternalJobDetails empty() {
		return new ExternalJobDetails(null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, null);
	}

	/** A vaga ainda aparece na busca? Removida ou vencida não aparece. */
	public boolean active(Instant now) {
		if (removedAt != null) {
			return false;
		}
		return expiresAt == null || expiresAt.isAfter(now);
	}

	/** Mesma vaga, com a última verificação renovada (o refresh viu a vaga na fonte). */
	public ExternalJobDetails seenAt(Instant when) {
		return new ExternalJobDetails(publishedAt, employmentType, level, workModel, city, state, country,
			salaryMinAmount, salaryMaxAmount, salaryCurrency, salaryPeriodCode, attributionUrl, when, expiresAt,
			removedAt);
	}

	/** Mesma vaga, marcada como removida da listagem ativa. */
	public ExternalJobDetails removedAt(Instant when) {
		return new ExternalJobDetails(publishedAt, employmentType, level, workModel, city, state, country,
			salaryMinAmount, salaryMaxAmount, salaryCurrency, salaryPeriodCode, attributionUrl, lastSeenAt,
			expiresAt, when);
	}
}
