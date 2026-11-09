package com.socialcoisa.rede.identity.application.port.out;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.identity.domain.ReleasePolicy;
import com.socialcoisa.rede.identity.domain.ReleaseRound;

/**
 * Persistencia da liberacao progressiva: a politica vigente, as rodadas e a fila de candidatos.
 *
 * <p>A fila NAO e um segundo sistema de reputacao: ela le {@code users.reputation}, a coluna que
 * ja existe e ja e mantida pelo worker de credibilidade. Credibilidade aqui e criterio de
 * PRIORIDADE, nunca de aprovacao — quem entra na fila continua sem garantia de vez naquele mes.</p>
 */
public interface ReleaseRepository {

	ReleasePolicy policy(Instant now);

	void savePolicy(ReleasePolicy policy);

	List<ReleaseRound> rounds(int limit);

	ReleaseRound round(String id);

	void saveRound(ReleaseRound round);

	/**
	 * Candidatos em ordem de prioridade: credibilidade maior primeiro; empate resolvido por quem
	 * participa ha mais tempo; novo empate resolvido pela conta mais antiga. Exclui quem ja tem o
	 * selo, quem esta com verificacao em andamento, quem tem sancao ativa, quem ja foi liberado e
	 * contas removidas.
	 */
	List<Candidate> candidates(int minReputation, int limit);

	/** Quantas verificacoes estao em andamento agora (limite de simultaneas). */
	int inFlight();

	long totalUsers();

	long releasedUsers();

	record Candidate(String userId, int reputation, Instant createdAt) {
	}
}