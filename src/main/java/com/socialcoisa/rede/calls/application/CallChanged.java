package com.socialcoisa.rede.calls.application;

import java.util.Collection;

/**
 * "A chamada X mudou" — sinal de tela, não conteúdo. O stream de mensagens repassa com o id da
 * conversa/grupo; o cliente busca o estado pelo endpoint normal, que confere permissão de novo.
 */
public record CallChanged(String callId, String conversationId, String groupId, String state,
		Collection<String> userIds) {
}
