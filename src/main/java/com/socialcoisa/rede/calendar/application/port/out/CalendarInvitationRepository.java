package com.socialcoisa.rede.calendar.application.port.out;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.calendar.domain.CalendarParticipantState;
import com.socialcoisa.rede.calendar.domain.CalendarScope;

/**
 * A leitura da agenda de quem foi convidado. A interface não conhece JPA: o cruzamento entre
 * compromisso e convite é do banco, que devolve a resposta junto com a entrada.
 *
 * <p>Uma consulta por janela, não duas: antes o chamador carregava os compromissos convidados e
 * devolvia os ids ao banco num `in` de tamanho variável para buscar as respostas.
 */
public interface CalendarInvitationRepository {

	/**
	 * O compromisso convidado com a resposta de quem está lendo, dentro da janela meia-aberta.
	 *
	 * <p>Devolve todo convite com linha de participante, inclusive o pendente e o de compromisso
	 * cancelado: a agenda mostra o pendente e o export precisa publicar o cancelamento com o `UID`
	 * que sempre foi dele. Quem filtra por estado é quem chama, que conhece a regra de cada leitura.
	 *
	 * @param scope nulo significa as duas agendas (a visão consolidada)
	 */
	List<InvitedEntry> findInvited(String userId, CalendarScope scope, Instant windowStart, Instant windowEnd);

	/** A entrada que outra pessoa organizou e o lugar de quem está lendo nela. */
	record InvitedEntry(com.socialcoisa.rede.calendar.domain.CalendarEntry entry,
			CalendarParticipantState answer) {
	}

}