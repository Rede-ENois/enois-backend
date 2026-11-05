package com.socialcoisa.rede.calls.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estados de uma chamada, com as transições permitidas.
 *
 * <p>A máquina é fechada de propósito: responder duas vezes, encerrar depois de encerrada ou
 * aceitar uma chamada que já venceu não é "quase certo", é recusa explícita. É o servidor que
 * decide, então dois cliques em "atender" e "recusar" ao mesmo tempo não podem deixar a
 * chamada nos dois estados.
 */
public enum CallState {
	/** Pedido aceito pelo servidor, notificação saindo. Quase invisível: só para o 1:1. */
	INITIATING,
	/** Toca para quem foi chamado (só 1:1). */
	RINGING,
	/** Alguém entrou na sala e a mídia está subindo. */
	CONNECTING,
	/** Há pelo menos uma sessão de mídia ativa (a nossa ou a de outra pessoa). */
	ACTIVE,
	/** Quem foi chamado recusou. */
	DECLINED,
	/** Ninguém atendeu antes do prazo. */
	MISSED,
	/** Quem chamou desistiu antes do atendimento. */
	CANCELLED,
	/** Chamada encerrada normalmente (depois de atendida). */
	ENDED,
	/** Falha de conexão/mídia declarada pelo cliente ou por expiração de sessão abandonada. */
	FAILED;

	/** Estados em que a chamada ainda existe e pode avançar. */
	public boolean isOpen() {
		return this == INITIATING || this == RINGING || this == CONNECTING || this == ACTIVE;
	}

	/** Chegou ao fim: ninguém entra mais, nada mais muda. */
	public boolean isTerminal() {
		return !isOpen();
	}

	/** Estados em que entrar na sala faz sentido. */
	public boolean allowsJoin() {
		return this == CONNECTING || this == ACTIVE;
	}

	private static final Set<CallState> FROM_INITIATING = EnumSet.of(RINGING, CONNECTING, ACTIVE, CANCELLED,
		DECLINED, MISSED, FAILED);
	private static final Set<CallState> FROM_RINGING = EnumSet.of(CONNECTING, ACTIVE, DECLINED, MISSED, CANCELLED,
		FAILED);
	private static final Set<CallState> FROM_CONNECTING = EnumSet.of(ACTIVE, ENDED, FAILED, MISSED);
	private static final Set<CallState> FROM_ACTIVE = EnumSet.of(ENDED, FAILED);

	public boolean canMoveTo(CallState next) {
		if (next == null || next == this) {
			return false;
		}
		return switch (this) {
			case INITIATING -> FROM_INITIATING.contains(next);
			case RINGING -> FROM_RINGING.contains(next);
			case CONNECTING -> FROM_CONNECTING.contains(next);
			case ACTIVE -> FROM_ACTIVE.contains(next);
			case DECLINED, MISSED, CANCELLED, ENDED, FAILED -> false;
		};
	}
}
