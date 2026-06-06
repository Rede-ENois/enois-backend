package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.messaging.application.port.out.MessagingRepository;
import com.socialcoisa.rede.messaging.domain.Conversation;
import com.socialcoisa.rede.messaging.domain.ConversationParticipant;
import com.socialcoisa.rede.messaging.domain.Message;
import com.socialcoisa.rede.shared.web.CursorCodec;

@Repository
class MessagingPersistenceAdapter implements MessagingRepository {

	private final SpringDataConversationJpaRepository conversationRepo;
	private final SpringDataConversationParticipantJpaRepository participantRepo;
	private final SpringDataMessageJpaRepository messageRepo;

	MessagingPersistenceAdapter(SpringDataConversationJpaRepository conversationRepo,
			SpringDataConversationParticipantJpaRepository participantRepo,
			SpringDataMessageJpaRepository messageRepo) {
		this.conversationRepo = conversationRepo;
		this.participantRepo = participantRepo;
		this.messageRepo = messageRepo;
	}

	@Override
	public void saveConversation(Conversation conversation) {
		conversationRepo.save(ConversationJpaEntity.fromDomain(conversation));
	}

	@Override
	public void saveParticipant(ConversationParticipant participant) {
		participantRepo.save(ConversationParticipantJpaEntity.fromDomain(participant));
	}

	@Override
	public Optional<Conversation> findConversationById(String id) {
		return conversationRepo.findById(id).map(ConversationJpaEntity::toDomain);
	}

	@Override
	public List<Conversation> findConversationsByUserId(String userId, int limit) {
		return conversationRepo.findByParticipantUserId(userId).stream()
			.map(ConversationJpaEntity::toDomain)
			.limit(limit)
			.toList();
	}

	@Override
	public List<ConversationParticipant> findParticipantsByConversation(String conversationId) {
		return participantRepo.findByConversationId(conversationId).stream()
			.map(ConversationParticipantJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Optional<ConversationParticipant> findParticipant(String conversationId, String userId) {
		return participantRepo.findByConversationIdAndUserId(conversationId, userId)
			.map(ConversationParticipantJpaEntity::toDomain);
	}

	@Override
	public boolean isParticipant(String conversationId, String userId) {
		return participantRepo.existsByConversationIdAndUserId(conversationId, userId);
	}

	@Override
	public void markRead(String conversationId, String userId, Instant now) {
		participantRepo.findByConversationIdAndUserId(conversationId, userId).ifPresent(entity -> {
			entity.markRead(now);
			participantRepo.save(entity);
		});
	}

	@Override
	public int countUnread(String conversationId, String userId) {
		var participant = participantRepo.findByConversationIdAndUserId(conversationId, userId).orElse(null);
		if (participant == null || participant.getLastReadAt() == null) {
			return (int) messageRepo.count();
		}
		return messageRepo.countUnreadAfter(conversationId, participant.getLastReadAt());
	}

	@Override
	public void saveMessage(Message message) {
		messageRepo.save(MessageJpaEntity.fromDomain(message));
	}

	@Override
	public List<Message> findMessagesByConversation(String conversationId, String cursor, int limit) {
		var messages = messageRepo.findByConversationIdOrderByCreatedAtDesc(conversationId, PageRequest.of(0, limit));
		return messages.stream().map(MessageJpaEntity::toDomain).toList();
	}

	@Override
	public Optional<Message> findLastMessage(String conversationId) {
		return messageRepo.findTopByConversationIdOrderByCreatedAtDesc(conversationId)
			.map(MessageJpaEntity::toDomain);
	}

}
