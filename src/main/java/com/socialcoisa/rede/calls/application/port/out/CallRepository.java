package com.socialcoisa.rede.calls.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.calls.domain.CallParticipantState;
import com.socialcoisa.rede.calls.domain.CallSession;
import com.socialcoisa.rede.calls.domain.CallState;

/**
 * Persistência das chamadas. As transições que podem chegar em paralelo (atender e recusar ao
 * mesmo tempo, dois "entrar") são condicionais: a linha só muda se ainda estiver no estado
 * esperado, e a operação devolve se ganhou a corrida. Ninguém sobrescreve o outro em silêncio.
 */
public interface CallRepository {

	/** Insere a chamada e as pessoas convidadas no mesmo commit. */
	void create(CallSession call, Collection<String> invitedUserIds, Instant now);

	/** Relê a linha e aplica a transição feita no domínio, se a linha ainda estiver em {@code from}. */
	boolean persist(CallSession call, CallState from);

	/**
	 * Grava só o prazo e o sinal de vida de uma chamada aberta, sem trocar de estado: é o que
	 * segura a sala vazia dentro da tolerância. Condicional ao estado lido.
	 */
	boolean extend(CallSession call, CallState state);

	Optional<CallSession> findById(String callId);

	/**
	 * Chamada dona de um nome de sala. O servidor de reunião só conhece o nome da sala; é por aqui
	 * que ele chega à chamada (e, dela, a quem pode entrar).
	 */
	Optional<CallSession> findByRoomId(String roomId);

	/** Chamada aberta de uma conversa — evita duas chamadas na mesma DM. */
	Optional<CallSession> findOpenByConversation(String conversationId);

	/** Chamada "ao vivo" (aberta) da sala geral do grupo (não é de tópico). */
	Optional<CallSession> findOpenByGroup(String groupId);

	/** Chamada "ao vivo" (aberta) do tópico do grupo. */
	Optional<CallSession> findOpenByTopic(String groupId, String topicId);

	/** Chamada "ao vivo" (aberta) do encontro. */
	Optional<CallSession> findOpenByEvent(String eventId);

	/** Chamadas abertas em que a pessoa está dentro ou foi convidada — para retomar a tela. */
	List<CallSession> findOpenByParticipant(String userId);

	CallParticipantState participantState(String callId, String userId);

	List<CallParticipantState> participantStates(String callId);

	/** Quem já esteve na sala (entrou pelo menos uma vez), na ordem em que entrou. */
	List<String> participantsWhoJoined(String callId);

	/** Quem está na sala agora (JOINED), sem contar a própria pessoa. */
	int countPresentExcluding(String callId, String excludedUserId);

	int countPresentAmong(String callId, Collection<String> userIds);

	/** Entra na sala, se ainda não estiver dentro. Falso = já estava (repetição idempotente). */
	boolean join(String callId, String userId, Instant now);

	/**
	 * Entrada numa sala aberta (encontro), onde não há lista de convidados: a linha de presença nasce
	 * aqui. Idempotente — entrar de novo mantém o primeiro horário.
	 */
	boolean joinOpenRoom(String callId, String userId, Instant now);

	/** Sai da sala; falso quando não estava dentro. */
	boolean leave(String callId, String userId, Instant now);

	/** Decisão em chamada 1:1 (atender, recusar): só muda quem ainda não decidiu. */
	boolean decide(String callId, String userId, CallParticipantState next, Instant now);

	/** Convites pendentes viram MISSED (não atendida) quando a chamada acaba. */
	void settleInvites(String callId, CallParticipantState next, Instant now);

	/** Abandonadas: marca a saída de todo mundo e conclui a chamada em uma transação só. */
	void abandon(CallSession call, CallState from, Instant now, String reason);

	List<CallSession> findExpired(Instant now, int limit);

	void logEntry(String callId, String userId, boolean granted, String reason);
}
