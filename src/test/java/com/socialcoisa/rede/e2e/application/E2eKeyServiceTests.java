package com.socialcoisa.rede.e2e.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.e2e.application.port.out.ConversationParticipantReader;
import com.socialcoisa.rede.e2e.application.port.out.E2eKeyRepository;
import com.socialcoisa.rede.e2e.domain.ConversationKey;
import com.socialcoisa.rede.e2e.domain.UserKeypair;
import com.socialcoisa.rede.shared.web.ApiException;

/** Testes unitários do E2eKeyService sem Spring: in-memory fakes para os dois ports. */
class E2eKeyServiceTests {

	private InMemoryE2eKeyRepository keyRepo;
	private FakeParticipantReader participantReader;
	private E2eKeyService service;

	@BeforeEach
	void setUp() {
		keyRepo = new InMemoryE2eKeyRepository();
		participantReader = new FakeParticipantReader();
		service = new E2eKeyService(keyRepo, participantReader,
			Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneId.of("UTC")));
	}

	@Test
	void uploadKeypairSavesAndDeactivatesPrevious() {
		String first = service.uploadKeypair("u1", "pub1", "priv1", "RSA-OAEP");
		assertThat(keyRepo.findActiveKeypair("u1")).isPresent();

		String second = service.uploadKeypair("u1", "pub2", "priv2", null);
		assertThat(second).isNotEqualTo(first);
		UserKeypair active = keyRepo.findActiveKeypair("u1").orElseThrow();
		assertThat(active.publicKey()).isEqualTo("pub2");
		assertThat(active.algorithm()).isEqualTo("RSA-OAEP");
	}

	@Test
	void uploadKeypairRefusesNullKeys() {
		assertThatThrownBy(() -> service.uploadKeypair("u1", null, "priv", null))
			.isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_KEYPAIR"));
	}

	@Test
	void uploadKeypairRefusesOversizedKeys() {
		String big = "x".repeat(9000);
		assertThatThrownBy(() -> service.uploadKeypair("u1", big, "priv", null))
			.isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("KEY_TOO_LARGE"));
	}

	@Test
	void distributeKeyRequiresParticipant() {
		participantReader.add("conv1", Set.of("u1", "u2"));

		assertThatThrownBy(() -> service.distributeConversationKey("outsider", "conv1",
				List.of(new E2eKeyService.ConversationKeyEntry("u1", "key"))))
			.isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("NOT_PARTICIPANT"));
	}

	@Test
	void distributeKeyRefusesNonParticipantTarget() {
		participantReader.add("conv1", Set.of("u1", "u2"));

		assertThatThrownBy(() -> service.distributeConversationKey("u1", "conv1",
				List.of(new E2eKeyService.ConversationKeyEntry("stranger", "key"))))
			.isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_PARTICIPANT"));
	}

	@Test
	void distributeKeySavesForAllParticipants() {
		participantReader.add("conv1", Set.of("u1", "u2"));

		service.distributeConversationKey("u1", "conv1", List.of(
			new E2eKeyService.ConversationKeyEntry("u1", "key-u1"),
			new E2eKeyService.ConversationKeyEntry("u2", "key-u2")));

		assertThat(keyRepo.findConversationKey("conv1", "u1")).isPresent();
		assertThat(keyRepo.findConversationKey("conv1", "u2")).isPresent();
		assertThat(keyRepo.findConversationKey("conv1", "u2").get().encryptedKey()).isEqualTo("key-u2");
	}

	@Test
	void conversationKeyRequiresParticipant() {
		participantReader.add("conv1", Set.of("u1"));

		assertThatThrownBy(() -> service.conversationKey("conv1", "outsider"))
			.isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("NOT_PARTICIPANT"));
	}

	@Test
	void conversationKeyReturnsEmptyWhenNotDistributed() {
		participantReader.add("conv1", Set.of("u1"));

		assertThat(service.conversationKey("conv1", "u1")).isEmpty();
	}

	// ── Fakes ──────────────────────────────────────────────────────────────────

	static class InMemoryE2eKeyRepository implements E2eKeyRepository {

		private final List<UserKeypair> keypairs = new ArrayList<>();
		private final Map<String, ConversationKey> conversationKeys = new LinkedHashMap<>();

		@Override
		public void saveKeypair(UserKeypair keypair) {
			keypairs.add(keypair);
		}

		@Override
		public Optional<UserKeypair> findActiveKeypair(String userId) {
			return keypairs.stream()
				.filter(k -> k.userId().equals(userId) && k.active())
				.reduce((a, b) -> b);
		}

		@Override
		public List<UserKeypair> findKeypairsByUser(String userId) {
			return keypairs.stream().filter(k -> k.userId().equals(userId)).toList();
		}

		@Override
		public void deactivateKeypairs(String userId) {
			List<UserKeypair> active = keypairs.stream()
				.filter(k -> k.userId().equals(userId) && k.active()).toList();
			for (UserKeypair kp : active) {
				keypairs.remove(kp);
				keypairs.add(new UserKeypair(kp.id(), kp.userId(), kp.publicKey(),
					kp.encryptedPrivateKey(), kp.algorithm(), false, kp.createdAt()));
			}
		}

		@Override
		public void saveConversationKey(ConversationKey key) {
			conversationKeys.put(key.conversationId() + ":" + key.userId(), key);
		}

		@Override
		public Optional<ConversationKey> findConversationKey(String conversationId, String userId) {
			return Optional.ofNullable(conversationKeys.get(conversationId + ":" + userId));
		}

		@Override
		public List<ConversationKey> findConversationKeys(String conversationId) {
			return conversationKeys.values().stream()
				.filter(k -> k.conversationId().equals(conversationId)).toList();
		}
	}

	static class FakeParticipantReader implements ConversationParticipantReader {

		private final Map<String, Set<String>> conversations = new LinkedHashMap<>();

		void add(String conversationId, Set<String> userIds) {
			conversations.put(conversationId, userIds);
		}

		@Override
		public boolean isParticipant(String conversationId, String userId) {
			return conversations.getOrDefault(conversationId, Set.of()).contains(userId);
		}

		@Override
		public Set<String> participantIds(String conversationId) {
			return conversations.getOrDefault(conversationId, Set.of());
		}
	}
}
