package com.socialcoisa.rede.jobs.domain;

/**
 * Estado da entrevista marcada pelo empregador.
 *
 * <p>Não existe "concluída": o sistema não sabe se a conversa aconteceu, e inventar esse estado
 * faria a agenda mentir. A entrevista fica marcada até ser cancelada ou reagendada.
 */
public enum JobInterviewStatus {

	/** Marcada e valendo. */
	SCHEDULED,

	/** Desmarcada pelo empregador; a entrada na agenda dos dois lados é cancelada. */
	CANCELLED;

	public boolean canCancel() {
		return this == SCHEDULED;
	}
}
