package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Pageable;

import org.springframework.dao.DataIntegrityViolationException;

import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.messaging.application.port.out.MessagingRepository;
import com.socialcoisa.rede.messaging.domain.Conversation;
import com.socialcoisa.rede.messaging.domain.ConversationContext;
import com.socialcoisa.rede.messaging.domain.ConversationParticipant;
import com.socialcoisa.rede.messaging.domain.Message;
import com.socialcoisa.rede.shared.web.CursorCodec;

@Repository
class MessagingPersistenceAdapter implements MessagingRepository {

	private final SpringDataConversationJpaRepository conversationRepo;
	private final SpringDataConversationParticipantJpaRepository participantRepo;
	private final SpringDataMessageJpaRepository messageRepo;
	private final SpringDataMessageReactionJpaRepository messageReactionRepo;
	private final SpringDataMessageHiddenJpaRepository messageHiddenRepo;

	MessagingPersistenceAdapter(SpringDataConversationJpaRepository conversationRepo,
			SpringDataConversationParticipantJpaRepository participantRepo,
			SpringDataMessageJpaRepository messageRepo,
			SpringDataMessageReactionJpaRepository messageReactionRepo,
			SpringDataMessageHiddenJpaRepository messageHiddenRepo) {
		this.conversationRepo = conversationRepo;
		this.participantRepo = participantRepo;
		this.messageRepo = messageRepo;
		this.messageReactionRepo = messageReactionRepo;
		this.messageHiddenRepo = messageHiddenRepo;
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
	public Optional<Conversation> findPairByKind(String userA, String userB, String kind) {
		return conversationRepo.findPairByKind(userA, userB, kind, PageRequest.of(0, 1))
			.stream()
			.findFirst()
			.map(ConversationJpaEntity::toDomain);
	}

	@Override
	public Optional<Conversation> findConversationById(String id) {
		return conversationRepo.findById(id).map(ConversationJpaEntity::toDomain);
	}

	@Override
	public List<Conversation> findConversationsByUserId(String userId, java.time.Instant before,
			ConversationContext context, int limit) {
		return conversationRepo.findByParticipantUserId(userId, before, contextName(context), PageRequest.of(0, limit))
			.stream()
			.map(ConversationJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Conversation> findArchivedConversationsByUserId(String userId, java.time.Instant before,
			ConversationContext context, int limit) {
		return conversationRepo.findArchivedByParticipantUserId(userId, before, contextName(context),
				PageRequest.of(0, limit))
			.stream()
			.map(ConversationJpaEntity::toDomain)
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
	public int hideConversations(String userId, java.util.Collection<String> conversationIds, java.time.Instant now) {
		if (conversationIds == null || conversationIds.isEmpty()) {
			return 0;
		}
		return participantRepo.hideConversations(userId, conversationIds, now);
	}

	@Override
	public int unhideConversations(String userId, java.util.Collection<String> conversationIds) {
		if (conversationIds == null || conversationIds.isEmpty()) {
			return 0;
		}
		return participantRepo.unhideConversations(userId, conversationIds);
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
			return messageRepo.countByConversationId(conversationId);
		}
		return messageRepo.countUnreadAfter(conversationId, participant.getLastReadAt());
	}

	@Override
	public int countTotalUnread(String userId, ConversationContext context) {
		return participantRepo.countTotalUnread(userId, contextName(context));
	}

	private static String contextName(ConversationContext context) {
		return context == null ? null : context.name();
	}

	@Override
	public void saveMessage(Message message) {
		messageRepo.save(MessageJpaEntity.fromDomain(message));
	}

	@Override
	public List<Message> findMessagesByConversation(String conversationId, String viewerId,
			java.time.Instant before, int limit) {
		var page = PageRequest.of(0, limit);
		var messages = before == null
			? messageRepo.visibleMessages(conversationId, viewerId, page)
			: messageRepo.visibleMessagesBefore(conversationId, viewerId, before, page);
		return messages.stream().map(MessageJpaEntity::toDomain).toList();
	}

	@Override
	public Optional<Message> findRecentDuplicate(String conversationId, String senderId, String text,
			java.time.Instant since) {
		return messageRepo
			.findFirstByConversationIdAndSenderIdAndTextAndCreatedAtAfterOrderByCreatedAtDesc(
				conversationId, senderId, text, since)
			.map(MessageJpaEntity::toDomain);
	}

	@Override
	public Optional<Message> findLastMessage(String conversationId) {
		return messageRepo.findTopByConversationIdOrderByCreatedAtDesc(conversationId)
			.map(MessageJpaEntity::toDomain);
	}

	@Override
	public long countMessagesInConversation(String conversationId, java.util.Collection<String> messageIds) {
		return messageRepo.countInConversation(conversationId, messageIds);
	}

	@Override
	public int hideMessages(String userId, java.util.Collection<String> messageIds, java.time.Instant now) {
		if (messageIds == null || messageIds.isEmpty()) {
			return 0;
		}
		int hidden = 0;
		for (String messageId : messageIds) {
			// Idempotente: apagar de novo nao cria linha repetida (a unique tambem protege).
			if (messageHiddenRepo.existsByUserIdAndMessageId(userId, messageId)) {
				continue;
			}
			messageHiddenRepo.save(MessageHiddenJpaEntity.of(
				"mhid_" + java.util.UUID.randomUUID().toString().replace("-", ""), userId, messageId, now));
			hidden++;
		}
		return hidden;
	}

	@Override
	public java.util.Set<String> hiddenMessageIds(String userId, java.util.Collection<String> messageIds) {
		if (messageIds == null || messageIds.isEmpty()) {
			return java.util.Set.of();
		}
		return new java.util.LinkedHashSet<>(messageHiddenRepo.hiddenIds(userId, messageIds));
	}

	@Override
	public Optional<Message> findMessageById(String messageId) {
		return messageRepo.findById(messageId).map(MessageJpaEntity::toDomain);
	}

	@Override
	public List<Message> findMessagesByIds(java.util.Collection<String> messageIds) {
		if (messageIds == null || messageIds.isEmpty()) return List.of();
		return messageRepo.findAllById(messageIds).stream().map(MessageJpaEntity::toDomain).toList();
	}

	@Override
	public boolean addMessageReaction(String id, String messageId, String userId, ReactionType type, Instant createdAt) {
		var existing = messageReactionRepo.findByMessageIdAndUserId(messageId, userId);
		if (existing.isPresent()) {
			var old = existing.get();
			if (old.getReactionType() == type) {
				messageReactionRepo.delete(old);
				return false;
			}
			messageReactionRepo.delete(old);
			messageReactionRepo.flush();
		}
		try {
			messageReactionRepo.saveAndFlush(new MessageReactionJpaEntity(id, messageId, userId, type, createdAt));
			return true;
		}
		catch (DataIntegrityViolationException exception) {
			return false;
		}
	}

	@Override
	public boolean removeMessageReaction(String messageId, String userId) {
		return messageReactionRepo.findByMessageIdAndUserId(messageId, userId)
			.map(reaction -> {
				messageReactionRepo.delete(reaction);
				return true;
			})
			.orElse(false);
	}

	@Override
	public List<Message> findAllBySender(String senderId) {
		return messageRepo.findBySenderId(senderId).stream()
			.map(MessageJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<MessagingRepository.ReactionSummary> findReactionsByMessageId(String messageId) {
		return messageReactionRepo.findByMessageIdOrderByCreatedAtDesc(messageId).stream()
			.map(r -> new MessagingRepository.ReactionSummary(r.getUserId(), r.getReactionType(), r.getCreatedAt()))
			.toList();
	}

	@Override
	public List<MessagingRepository.MessageReactionRow> findReactionsByMessageIds(java.util.Collection<String> messageIds) {
		if (messageIds == null || messageIds.isEmpty()) {
			return List.of();
		}
		return messageReactionRepo.findByMessageIdIn(messageIds).stream()
			.map(r -> new MessagingRepository.MessageReactionRow(r.getMessageId(), r.getUserId(), r.getReactionType()))
			.toList();
	}
}
