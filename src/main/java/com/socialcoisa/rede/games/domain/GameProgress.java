package com.socialcoisa.rede.games.domain;

import java.time.Instant;

/**
 * O progresso de um jogo para uma conta: trocar de aparelho ou limpar dados do navegador não
 * pode perder o histórico (relato do suporte que originou o épico). O estado é OPACO para o
 * backend — cada jogo (do desenvolvedor ou diário da casa) guarda o que precisa, e o servidor
 * não interpreta nem valida o conteúdo: só o jogo sabe o que "fase 3" significa.
 *
 * Último estado vence: o PUT substitui o estado inteiro. O jogo que quiser mesclar (partida a
 * partida) mescla no cliente antes de mandar — decisão simples primeiro, merge sofisticado só
 * se o relato pedir.
 */
public record GameProgress(String id, String gameId, String userId, String state, Instant createdAt,
		Instant updatedAt) {

	/** Um progresso nasce sem estado (o jogo começa do zero do lado do cliente). */
	public static GameProgress start(String id, String gameId, String userId, String state, Instant now) {
		return new GameProgress(id, gameId, userId, state, now, now);
	}

	public GameProgress withState(String newState, Instant now) {
		return new GameProgress(id, gameId, userId, newState, createdAt, now);
	}
}
