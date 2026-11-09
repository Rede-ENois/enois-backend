package com.socialcoisa.rede.identity.domain;

import java.time.Instant;

/**
 * Uma rodada de liberacao: quantos foram liberados, a partir de qual credibilidade e em que faixa
 * do ranking. Fica gravada para sempre — e a prova de que ninguem foi liberado a mao nem fora de
 * ordem.
 */
public record ReleaseRound(String id, Instant executedAt, int eligibleCount, int releasedCount,
		int minReputation, int rankStart, int rankEnd, ReleaseRoundState state, int reservedCapacityUsed,
		String note) {
}