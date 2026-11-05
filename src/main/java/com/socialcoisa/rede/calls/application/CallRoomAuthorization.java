package com.socialcoisa.rede.calls.application;

import java.util.Optional;

import com.socialcoisa.rede.calls.domain.CallSession;

/**
 * Autorização de entrada na sala de uma chamada, identificada pelo nome da sala.
 *
 * <p>É o único ponto que responde "esta pessoa pode entrar nesta sala?", e ele existe porque a
 * assinatura do token pode acontecer fora daqui (servidor de reunião próprio com gateway de
 * identidade). Nesse caso o gateway pergunta antes de emitir o token, e a resposta vem desta
 * regra — que lê o banco a cada pedido. O gateway nunca decide sozinho quem entra onde.
 */
public interface CallRoomAuthorization {

	/** A chamada à qual a sala pertence, se ela existir. */
	Optional<CallSession> callOfRoom(String room);

	/**
	 * Confere o vínculo da pessoa com a chamada (participante da conversa ou membro do grupo, na
	 * hora) e, quando permitido, registra a entrada. Negar lança o erro certo para o chamador.
	 */
	CallEntry entry(String callId, String userId);

	/**
	 * A entrada autorizada. {@code room}, {@code userId}, {@code displayName} e {@code moderator}
	 * saem daqui para o gateway assinar o token — nunca de campos que o navegador mandou.
	 */
	record CallEntry(String callId, String room, String userId, String state, String displayName,
			boolean moderator) {
	}
}
