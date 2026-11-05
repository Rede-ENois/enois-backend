package com.socialcoisa.rede.e2e.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.e2e.application.port.out.ConversationParticipantReader;
import com.socialcoisa.rede.e2e.application.port.out.E2eKeyRepository;
import com.socialcoisa.rede.e2e.domain.ConversationKey;
import com.socialcoisa.rede.e2e.domain.UserKeypair;
import com.socialcoisa.rede.shared.web.ApiException;

/*** Gestão de chaves E2E: keypairs de usuário e chaves simétricas de conversa. ***/

@Service
public class E2eKeyService {

	private static final int MAX_KEY_SIZE = 8192;

	private final E2eKeyRepository keys;
	private final ConversationParticipantReader participants;
	private final Clock clock;

	public E2eKeyService(E2eKeyRepository keys, ConversationParticipantReader participants, Clock clock) {
		this.keys = keys;
		this.participants = participants;
		this.clock = clock;
	}

	public String uploadKeypair(String userId, String publicKey, String encryptedPrivateKey, String algorithm) {
		if (publicKey == null || encryptedPrivateKey == null) {
			throw new ApiException(400, "INVALID_KEYPAIR", "Chave publica e privada cifrada sao obrigatorias.");
		}
		if (publicKey.length() > MAX_KEY_SIZE || encryptedPrivateKey.length() > MAX_KEY_SIZE) {
			throw new ApiException(400, "KEY_TOO_LARGE", "Chave excede o tamanho maximo permitido.");
		}
		keys.deactivateKeypairs(userId);
		String id = UUID.randomUUID().toString();
		keys.saveKeypair(new UserKeypair(id, userId, publicKey, encryptedPrivateKey,
			algorithm != null ? algorithm : "RSA-OAEP", true, clock.instant()));
		return id;
	}

	public Optional<UserKeypair> activeKeypair(String userId) {
		return keys.findActiveKeypair(userId);
	}

	public void distributeConversationKey(String callerId, String conversationId,
			List<ConversationKeyEntry> entries) {
		requireParticipant(conversationId, callerId);
		Set<String> real = participants.participantIds(conversationId);
		for (ConversationKeyEntry entry : entries) {
			if (!real.contains(entry.userId())) {
				throw new ApiException(400, "INVALID_PARTICIPANT",
					"Usuario " + entry.userId() + " nao participa desta conversa.");
			}
			if (entry.encryptedKey() == null || entry.encryptedKey().length() > MAX_KEY_SIZE) {
				throw new ApiException(400, "INVALID_KEY", "Chave invalida ou excede o tamanho maximo.");
			}
			keys.saveConversationKey(new ConversationKey(conversationId, entry.userId(),
				entry.encryptedKey(), 1, clock.instant()));
		}
	}

	public Optional<ConversationKey> conversationKey(String conversationId, String userId) {
		requireParticipant(conversationId, userId);
		return keys.findConversationKey(conversationId, userId);
	}

	private void requireParticipant(String conversationId, String userId) {
		if (!participants.isParticipant(conversationId, userId)) {
			throw new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa.");
		}
	}

	public record ConversationKeyEntry(String userId, String encryptedKey) {
	}
}
