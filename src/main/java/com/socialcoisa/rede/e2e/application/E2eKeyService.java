package com.socialcoisa.rede.e2e.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

	/**
	 * Grava o keypair do usuário.
	 *
	 * Reenviar o MESMO par de chaves (mesma chave publica) so reescreve a copia cifrada da privada —
	 * e o que acontece quando a senha da conta muda e o cliente recifra a privada com a senha nova.
	 * Nesse caso a chave publica continua a mesma, entao toda conversa e toda mensagem continuam
	 * decifraveis.
	 *
	 * Um par de chaves NOVO desativa o anterior, mas nao o apaga: as chaves de conversa e as chaves
	 * por mensagem foram cifradas com a publica antiga e so a privada antiga abre. O cliente busca
	 * esse historico em {@link #keypairHistory} e continua lendo as mensagens antigas.
	 */
	public String uploadKeypair(String userId, String publicKey, String encryptedPrivateKey, String algorithm) {
		if (publicKey == null || encryptedPrivateKey == null) {
			throw new ApiException(400, "INVALID_KEYPAIR", "Chave publica e privada cifrada sao obrigatorias.");
		}
		if (publicKey.length() > MAX_KEY_SIZE || encryptedPrivateKey.length() > MAX_KEY_SIZE) {
			throw new ApiException(400, "KEY_TOO_LARGE", "Chave excede o tamanho maximo permitido.");
		}
		String alg = algorithm != null ? algorithm : "RSA-OAEP";
		Optional<UserKeypair> active = keys.findActiveKeypair(userId);
		if (active.isPresent() && active.get().publicKey().equals(publicKey)) {
			String id = active.get().id();
			keys.saveKeypair(new UserKeypair(id, userId, publicKey, encryptedPrivateKey, alg, true,
				active.get().createdAt()));
			return id;
		}
		keys.deactivateKeypairs(userId);
		String id = UUID.randomUUID().toString();
		keys.saveKeypair(new UserKeypair(id, userId, publicKey, encryptedPrivateKey, alg, true, clock.instant()));
		return id;
	}

	public Optional<UserKeypair> activeKeypair(String userId) {
		return keys.findActiveKeypair(userId);
	}

	/**
	 * Historico de keypairs do usuario, do mais recente pro mais antigo.
	 *
	 * O cliente usa a chave publica de cada item pra saber a qual keypair uma chave de conversa ou
	 * uma chave por mensagem pertence, e tenta decifrar com a privada correspondente.
	 */
	public List<UserKeypair> keypairHistory(String userId) {
		return keys.findKeypairsByUser(userId).stream()
			.sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
			.toList();
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

	/** Retorna a chave publica ativa de cada usuario da lista. Quem nao tem keypair nao aparece. */
	public Map<String, String> activePublicKeys(List<String> userIds) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String userId : userIds) {
			activeKeypair(userId).ifPresent(kp -> result.put(userId, kp.publicKey()));
		}
		return result;
	}

	public record ConversationKeyEntry(String userId, String encryptedKey) {
	}
}
