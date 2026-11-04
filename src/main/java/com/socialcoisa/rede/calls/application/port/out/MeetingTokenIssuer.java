package com.socialcoisa.rede.calls.application.port.out;

import com.socialcoisa.rede.calls.domain.CallSession;
import com.socialcoisa.rede.calls.domain.CallParticipant;

/**
 * Emite a credencial de entrada na sala de reunião.
 *
 * <p>A chave de assinatura vive só aqui, no servidor: o navegador recebe o token pronto, nunca o
 * segredo. Sem este componente configurado a chamada não abre (erro explícito), em vez de cair
 * numa sala pública aberta.
 */
public interface MeetingTokenIssuer {

	/** Há infraestrutura configurada para abrir salas? */
	boolean isConfigured();

	/** Endereço do servidor de reunião (o que o cliente embute no painel), sem barra no fim. */
	String host();

	/**
	 * Token de entrada de uma pessoa numa chamada. {@code moderator} só quando o dono da sala é
	 * quem iniciou a chamada — quem entra depois nunca vira moderador por conta própria.
	 */
	String tokenFor(CallSession call, CallParticipant participant, String displayName, boolean moderator);

	/** Tempo de validade do token, em segundos (o cliente não renova sozinho). */
	long tokenTtlSeconds();
}
