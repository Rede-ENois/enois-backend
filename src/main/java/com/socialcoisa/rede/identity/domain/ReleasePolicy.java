package com.socialcoisa.rede.identity.domain;

import java.time.Instant;

/**
 * Como a liberacao progressiva anda. Nenhum destes numeros fica no codigo: sao configuracoes
 * administrativas, e mudar qualquer um deles gera registro de auditoria.
 *
 * @param usersPerRound quantos convites cada rodada distribui
 * @param intervalMinutes de quanto em quanto tempo a rodada automatica roda
 * @param minReputation credibilidade minima para entrar na fila
 * @param reservedQuotaPercent quanto da capacidade mensal fica reservado (nao vai para rodada)
 * @param maxSimultaneous quantas verificacoes podem estar em andamento ao mesmo tempo
 * @param paused quando verdadeiro, nenhuma rodada roda (manual ou automatica)
 */
public record ReleasePolicy(int usersPerRound, int intervalMinutes, int minReputation, int reservedQuotaPercent,
		int maxSimultaneous, boolean paused, Instant nextRoundAt) {

	public static ReleasePolicy defaults(Instant now) {
		return new ReleasePolicy(5, 60 * 24, 0, 20, 3, false, now);
	}

	/** Capa o que a administracao possa digitar fora da faixa, em vez de confiar no formulario. */
	public ReleasePolicy sanitized() {
		return new ReleasePolicy(
			Math.max(1, Math.min(usersPerRound, 100)),
			Math.max(15, Math.min(intervalMinutes, 60 * 24 * 30)),
			Math.max(0, Math.min(minReputation, 1000)),
			Math.max(0, Math.min(reservedQuotaPercent, 90)),
			Math.max(1, Math.min(maxSimultaneous, 100)),
			paused,
			nextRoundAt);
	}
}