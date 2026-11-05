package com.socialcoisa.rede.calls.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.calls.application.port.out.CallRepository;
import com.socialcoisa.rede.calls.domain.CallModality;
import com.socialcoisa.rede.calls.domain.CallSession;
import com.socialcoisa.rede.groups.application.GroupService;
import com.socialcoisa.rede.groups.domain.GroupRole;
import com.socialcoisa.rede.messaging.application.MessagingService;
import com.socialcoisa.rede.messaging.domain.Conversation;
import com.socialcoisa.rede.messaging.domain.ConversationParticipant;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Quem pode tocar, quem pode entrar e quem pode saber que a chamada existe.
 *
 * <p>Esta é a única porta de autorização: o cliente não decide nada. O vínculo da chamada
 * (conversa ou grupo) é lido do banco a cada verificação, então editar a URL ou o corpo da
 * requisição não muda o resultado — entrar exige ser participante do vínculo.
 */
@Service
public class CallAudience {

	private final MessagingService messaging;
	private final GroupService groups;
	private final CallRepository calls;

	public CallAudience(MessagingService messaging, GroupService groups, CallRepository calls) {
		this.messaging = messaging;
		this.groups = groups;
		this.calls = calls;
	}

	public boolean isParticipant(String conversationId, String userId) {
		return conversationId != null && messaging.isParticipant(conversationId, userId);
	}

	/** Participantes da conversa (a lista completa, na ordem em que entram na chamada). */
	public List<String> participantsOf(String conversationId) {
		List<String> ids = new ArrayList<>();
		for (ConversationParticipant participant : messaging.getParticipants(conversationId)) {
			ids.add(participant.userId());
		}
		ids.sort(Comparator.naturalOrder());
		return ids;
	}

	public Conversation conversation(String conversationId) {
		return messaging.getConversation(conversationId);
	}

	/** Papel no grupo, ou nulo quando não é membro. */
	public GroupRole roleIn(String groupId, String userId) {
		return groups.roleOf(groupId, userId);
	}

	/**
	 * Membros do grupo para o anúncio da chamada. Lido direto do repositório: a lista de membros da
	 * API exige permissão de quem administra, e aqui quem convoca é o próprio grupo.
	 */
	public List<String> groupMembers(String groupId) {
		return groups.memberIds(groupId);
	}

	/**
	 * Exige que a pessoa possa agir na chamada: quem cria precisa estar no vínculo, quem entra
	 * precisa estar no vínculo E ter sido convidado (ou ser membro do grupo, para o "Tamo junto").
	 */
	public void requireMember(CallSession call, String userId) {
		if (call.conversationId() != null) {
			if (!isParticipant(call.conversationId(), userId)) {
				throw new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa.");
			}
			return;
		}
		if (call.groupId() != null) {
			if (roleIn(call.groupId(), userId) == null) {
				throw new ApiException(403, "NOT_GROUP_MEMBER", "Voce nao e membro deste grupo.");
			}
			return;
		}
		// Sem vínculo não há como provar nada: nega, em vez de abrir.
		throw new ApiException(403, "CALL_NOT_ALLOWED", "Chamada sem vinculo de conversa ou grupo.");
	}

	/**
	 * Para entrar na sala: quem começou a chamada entra; no 1:1 o chamado entra; no grupo, qualquer
	 * membro entra. A checagem do vínculo vem antes de qualquer outra coisa.
	 */
	public void requireCanJoin(CallSession call, String userId) {
		requireMember(call, userId);
		if (call.modality() == CallModality.DIRECT) {
			return;
		}
		// Grupo: ser membro basta. O convite é só a notificação; a sala é do grupo.
	}

	/** Participantes que a chamada deve notificar (todos menos quem criou). */
	public List<String> audienceOf(CallSession call) {
		List<String> all = call.conversationId() != null ? participantsOf(call.conversationId())
			: call.groupId() != null ? groupMembers(call.groupId()) : List.of();
		return all.stream().filter(id -> !id.equals(call.initiatorId())).toList();
	}
}
