package com.socialcoisa.rede.messaging.application;

import java.time.Instant;

import com.socialcoisa.rede.messaging.domain.ConversationRole;

/**
 * Quem participa de um grupo de conversa e com que papel. A identidade (nome, @, avatar) nao vem
 * aqui: o controller a monta com a leitura em lote dos perfis, como faz o resto da caixa de
 * entrada.
 */
public record GroupParticipant(String userId, ConversationRole role, Instant joinedAt) {
}
