package com.socialcoisa.rede.messaging.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.messaging.application.port.out.MessagingRepository;
import com.socialcoisa.rede.messaging.domain.Conversation;
import com.socialcoisa.rede.messaging.domain.ConversationParticipant;
import com.socialcoisa.rede.messaging.domain.Message;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class MessagingService {

	private final MessagingRepository repository;
	private final NotificationService notificationService;
	private final Clock clock;

	public MessagingService(MessagingRepository repository, NotificationService notificationService, Clock clock) {
		this.repository = repository;
		this.notificationService = notificationService;
		this.clock = clock;
	}

	@Transactional
	public Conversation createConversation(String kind, String title, List<String> participantIds, String createdBy) {
		if (!participantIds.contains(createdBy)) {
			participantIds = new ArrayList<>(participantIds);
			participantIds.add(createdBy);
		}
		String id = "conversation_" + UUID.randomUUID().toString().replace("-", "");
		Conversation conversation = Conversation.create(id, kind, title, createdBy, clock.instant());
		repository.saveConversation(conversation);
		for (String pid : participantIds) {
			repository.saveParticipant(ConversationParticipant.create(id, pid, clock.instant()));
		}
		return conversation;
	}

	@Transactional
	public Message sendMessage(String conversationId, String senderId, String text) {
		Conversation conversation = repository.findConversationById(conversationId)
			.orElseThrow(() -> new ApiException(404, "CONVERSATION_NOT_FOUND", "Conversa nao encontrada."));
		if (!repository.isParticipant(conversationId, senderId)) {
			throw new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa.");
		}
		String id = "message_" + UUID.randomUUID().toString().replace("-", "");
		Message message = Message.create(id, conversationId, senderId, text, clock.instant());
		repository.saveMessage(message);
		String preview = text.length() > 80 ? text.substring(0, 77) + "..." : text;
		List<ConversationParticipant> participants = repository.findParticipantsByConversation(conversationId);
		for (ConversationParticipant p : participants) {
			if (p.userId().equals(senderId)) continue;
			notificationService.create(p.userId(), "NEW_MESSAGE", senderId,
				"Nova mensagem", preview,
				"/api/conversations/" + conversationId, "Responder");
		}
		return message;
	}

	@Transactional
	public void markRead(String conversationId, String userId) {
		if (!repository.isParticipant(conversationId, userId)) {
			throw new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa.");
		}
		repository.markRead(conversationId, userId, clock.instant());
	}

	public List<Conversation> listConversations(String userId, java.time.Instant before, int limit) {
		return repository.findConversationsByUserId(userId, before, limit);
	}

	public Conversation getConversation(String id) {
		return repository.findConversationById(id)
			.orElseThrow(() -> new ApiException(404, "CONVERSATION_NOT_FOUND", "Conversa nao encontrada."));
	}

	public List<ConversationParticipant> getParticipants(String conversationId) {
		return repository.findParticipantsByConversation(conversationId);
	}

	public int countUnread(String conversationId, String userId) {
		return repository.countUnread(conversationId, userId);
	}

	public List<Message> getMessages(String conversationId, String cursor, int limit) {
		if (!repository.findConversationById(conversationId).isPresent()) {
			throw new ApiException(404, "CONVERSATION_NOT_FOUND", "Conversa nao encontrada.");
		}
		return repository.findMessagesByConversation(conversationId, cursor, limit);
	}

	public String getLastMessageText(String conversationId) {
		return repository.findLastMessage(conversationId)
			.map(Message::text)
			.orElse(null);
	}

	public java.time.Instant getLastMessageTime(String conversationId) {
		return repository.findLastMessage(conversationId)
			.map(Message::createdAt)
			.orElse(null);
	}

}
