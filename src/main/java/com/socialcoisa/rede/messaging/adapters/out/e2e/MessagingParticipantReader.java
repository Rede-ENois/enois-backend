package com.socialcoisa.rede.messaging.adapters.out.e2e;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.e2e.application.port.out.ConversationParticipantReader;
import com.socialcoisa.rede.messaging.application.port.out.MessagingRepository;

/**
 * O módulo de messaging implementa o port que o E2E define: quem participa de uma conversa.
 * A direção da dependência é messaging → e2e (implementa), nunca e2e → messaging.
 */
@Component
class MessagingParticipantReader implements ConversationParticipantReader {

	private final MessagingRepository repository;

	MessagingParticipantReader(MessagingRepository repository) {
		this.repository = repository;
	}

	@Override
	public boolean isParticipant(String conversationId, String userId) {
		return repository.isParticipant(conversationId, userId);
	}

	@Override
	public Set<String> participantIds(String conversationId) {
		return repository.findParticipantsByConversation(conversationId).stream()
			.map(p -> p.userId())
			.collect(Collectors.toSet());
	}
}
