package com.socialcoisa.rede.contributions.domain;

/**
 * Estado interno de uma evidência de contribuição. Só VALIDATED conta para selo; CONTESTED pede
 * revisão humana e REVOKED preserva o histórico sem manter o reconhecimento.
 */
public enum ContributionStatus {
	PENDING,
	VALIDATED,
	REJECTED,
	CONTESTED,
	REVOKED
}
