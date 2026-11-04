package com.socialcoisa.rede.calls.domain;

/** Situação de uma pessoa dentro da chamada (não confundir com o estado da chamada inteira). */
public enum CallParticipantState {
	/** Convidada e ainda não decidiu (1:1 toca; no grupo só aparece na lista de quem pode entrar). */
	INVITED,
	/** Está na sala agora. */
	JOINED,
	/** Saiu da sala sem encerrar a chamada para os outros (só é possível em grupo). */
	LEFT,
	/** Recusou explicitamente. */
	DECLINED,
	/** Não atendeu antes do prazo. */
	MISSED;

	public boolean isPresent() {
		return this == JOINED;
	}
}
