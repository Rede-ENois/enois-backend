package com.socialcoisa.rede.e2e.application.port.out;

import java.util.Set;

/**
 * Leitura dos participantes de uma conversa. O módulo E2E precisa validar se quem distribui
 * ou lê uma chave é participante da conversa, mas não conhece o domínio de mensagens.
 * O módulo de messaging implementa este port.
 */
public interface ConversationParticipantReader {

	boolean isParticipant(String conversationId, String userId);

	Set<String> participantIds(String conversationId);
}
