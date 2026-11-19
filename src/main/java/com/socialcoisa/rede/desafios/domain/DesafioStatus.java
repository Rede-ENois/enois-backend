package com.socialcoisa.rede.desafios.domain;

/** Os estados do desafio: no ar e aberto, encerrado como história, ou fora do ar. */
public enum DesafioStatus {
	/** No ar, recebendo participação (até `endsAt`, quando existe). */
	ACTIVE,
	/** Encerrado pelo autor, com o reconhecimento gravado no próprio desafio. */
	CLOSED,
	/** Fora do ar, pelo autor ou pela moderação. */
	REMOVED
}
