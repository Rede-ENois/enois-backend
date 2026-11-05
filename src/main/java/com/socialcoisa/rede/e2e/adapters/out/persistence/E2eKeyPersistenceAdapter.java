package com.socialcoisa.rede.e2e.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.e2e.application.port.out.E2eKeyRepository;
import com.socialcoisa.rede.e2e.domain.ConversationKey;
import com.socialcoisa.rede.e2e.domain.UserKeypair;

@Repository
class E2eKeyPersistenceAdapter implements E2eKeyRepository {

	private final SpringDataUserKeypairRepository keypairs;
	private final SpringDataConversationKeyRepository conversationKeys;

	E2eKeyPersistenceAdapter(SpringDataUserKeypairRepository keypairs,
			SpringDataConversationKeyRepository conversationKeys) {
		this.keypairs = keypairs;
		this.conversationKeys = conversationKeys;
	}

	@Override
	public void saveKeypair(UserKeypair keypair) {
		keypairs.save(new UserKeypairJpaEntity(keypair));
	}

	@Override
	public Optional<UserKeypair> findActiveKeypair(String userId) {
		return keypairs.findByUserIdAndActiveTrue(userId).map(UserKeypairJpaEntity::toDomain);
	}

	@Override
	public List<UserKeypair> findKeypairsByUser(String userId) {
		return keypairs.findByUserId(userId).stream().map(UserKeypairJpaEntity::toDomain).toList();
	}

	@Override
	@org.springframework.transaction.annotation.Transactional
	public void deactivateKeypairs(String userId) {
		keypairs.deactivateByUserId(userId);
	}

	@Override
	public void saveConversationKey(ConversationKey key) {
		conversationKeys.save(new ConversationKeyJpaEntity(key));
	}

	@Override
	public Optional<ConversationKey> findConversationKey(String conversationId, String userId) {
		return conversationKeys.findByConversationIdAndUserId(conversationId, userId)
			.map(ConversationKeyJpaEntity::toDomain);
	}

	@Override
	public List<ConversationKey> findConversationKeys(String conversationId) {
		return conversationKeys.findByConversationId(conversationId).stream()
			.map(ConversationKeyJpaEntity::toDomain).toList();
	}
}
