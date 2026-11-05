package com.socialcoisa.rede.e2e.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.e2e.application.E2eKeyService;
import com.socialcoisa.rede.e2e.domain.ConversationKey;
import com.socialcoisa.rede.e2e.domain.UserKeypair;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Endpoints E2E: gestão de keypairs e chaves de conversa.
 *
 * Controller fino: valida sessão e delega pro E2eKeyService. Nenhuma regra de negócio aqui.
 */
@RestController
class E2eKeyController {

	private final E2eKeyService e2eKeys;
	private final CurrentUserResolver currentUserResolver;

	E2eKeyController(E2eKeyService e2eKeys, CurrentUserResolver currentUserResolver) {
		this.e2eKeys = e2eKeys;
		this.currentUserResolver = currentUserResolver;
	}

	@PostMapping("/e2e/keypair")
	Map<String, String> uploadKeypair(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestBody KeypairRequest request) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		String id = e2eKeys.uploadKeypair(userId, request.publicKey(), request.encryptedPrivateKey(),
			request.algorithm());
		return Map.of("id", id, "status", "ACTIVE");
	}

	@GetMapping("/e2e/keypair")
	KeypairView myKeypair(@RequestHeader("X-Session-Token") String sessionToken) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		UserKeypair kp = e2eKeys.activeKeypair(userId)
			.orElseThrow(() -> new ApiException(404, "NO_KEYPAIR", "Nenhum keypair configurado."));
		return new KeypairView(kp.publicKey(), kp.encryptedPrivateKey(), kp.algorithm());
	}

	@GetMapping("/e2e/keypair/{userId}")
	PublicKeyView userPublicKey(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String userId) {
		currentUserResolver.resolve(sessionToken);
		return e2eKeys.activeKeypair(userId)
			.map(kp -> new PublicKeyView(kp.publicKey(), kp.algorithm()))
			.orElse(new PublicKeyView(null, null));
	}

	@PostMapping("/e2e/keypairs/batch")
	Map<String, String> batchPublicKeys(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestBody List<String> userIds) {
		currentUserResolver.resolve(sessionToken);
		if (userIds == null || userIds.size() > 50) {
			throw new ApiException(400, "INVALID_REQUEST", "Maximo 50 usuarios por batch.");
		}
		return e2eKeys.activePublicKeys(userIds);
	}

	@PostMapping("/e2e/conversations/{conversationId}/keys")
	Map<String, String> distributeKey(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String conversationId, @RequestBody List<ConversationKeyRequest> participants) {
		String callerId = currentUserResolver.resolve(sessionToken).id().value();
		e2eKeys.distributeConversationKey(callerId, conversationId,
			participants.stream()
				.map(p -> new E2eKeyService.ConversationKeyEntry(p.userId(), p.encryptedKey()))
				.toList());
		return Map.of("status", "DISTRIBUTED", "conversationId", conversationId);
	}

	@GetMapping("/e2e/conversations/{conversationId}/key")
	ConversationKeyView myConversationKey(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String conversationId) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		ConversationKey ck = e2eKeys.conversationKey(conversationId, userId).orElse(null);
		if (ck == null) {
			return new ConversationKeyView(null, 0);
		}
		return new ConversationKeyView(ck.encryptedKey(), ck.keyVersion());
	}

	record KeypairRequest(String publicKey, String encryptedPrivateKey, String algorithm) {}
	record KeypairView(String publicKey, String encryptedPrivateKey, String algorithm) {}
	record PublicKeyView(String publicKey, String algorithm) {}
	record ConversationKeyRequest(String userId, String encryptedKey) {}
	record ConversationKeyView(String encryptedKey, int keyVersion) {}
}
