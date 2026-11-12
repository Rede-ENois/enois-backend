package com.socialcoisa.rede.jobs.domain;

/**
 * Espécie de movimento do candidato no pipeline. REJECT não é etapa — é um fim, e o motivo
 * obrigatório mora no próprio movimento, não num campo solto da candidatura.
 */
public enum StageMovementKind {

	/** O candidato entrou na etapa de destino. */
	ADVANCE,

	/** O candidato voltou para uma etapa anterior (reavaliação, correção). */
	RETURN,

	/** O candidato foi reprovado a partir da etapa em que estava. */
	REJECT;

}
