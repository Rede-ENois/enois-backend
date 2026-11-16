package com.socialcoisa.rede.messaging.application;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.messaging.application.port.out.MessagingRepository;
import com.socialcoisa.rede.messaging.domain.Message;

@Service
public class MessageReplyReader {

	private final MessagingRepository repository;

	public MessageReplyReader(MessagingRepository repository) {
		this.repository = repository;
	}

	/** A citacao respeita a conversa e o mesmo "apagar para mim" do historico, em lote. */
	public List<Message> findVisible(String conversationId, String viewerId, Collection<String> messageIds) {
		if (messageIds.isEmpty()) {
			return List.of();
		}
		var hiddenIds = repository.hiddenMessageIds(viewerId, messageIds);
		return repository.findMessagesByIds(messageIds).stream()
			.filter(message -> message.conversationId().equals(conversationId))
			.filter(message -> !hiddenIds.contains(message.id()))
			.toList();
	}
}
