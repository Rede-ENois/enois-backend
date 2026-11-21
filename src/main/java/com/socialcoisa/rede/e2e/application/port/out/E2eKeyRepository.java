package com.socialcoisa.rede.e2e.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.e2e.domain.ConversationKey;
import com.socialcoisa.rede.e2e.domain.UserKeypair;

public interface E2eKeyRepository {

	void saveKeypair(UserKeypair keypair);
	Optional<UserKeypair> findActiveKeypair(String userId);
	List<UserKeypair> findKeypairsByUser(String userId);
	void deactivateKeypairs(String userId);

	void saveConversationKey(ConversationKey key);
	Optional<ConversationKey> findConversationKey(String conversationId, String userId);
	List<ConversationKey> findConversationKeys(String conversationId);

	/**
	 * Apaga a copia da chave da conversa que a pessoa tinha. Usada quando ela sai do grupo: a chave
	 * guardada no servidor nao pode continuar valendo para quem nao participa mais.
	 */
	void deleteConversationKey(String conversationId, String userId);
}
