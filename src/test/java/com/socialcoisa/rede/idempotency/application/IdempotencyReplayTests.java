package com.socialcoisa.rede.idempotency.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;

import com.socialcoisa.rede.idempotency.adapters.out.persistence.IdempotencyKeyEntity;
import com.socialcoisa.rede.idempotency.adapters.out.persistence.SpringDataIdempotencyKeyRepository;
import com.socialcoisa.rede.shared.web.ApiException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Testa os branches de replayOrReject que sao dificeis de alcancar via integracao:
 * PROCESSING (concorrencia), FAILED (defensivo), e a corrida de insert com fallback.
 */
class IdempotencyReplayTests {

	private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private SpringDataIdempotencyKeyRepository repository;
	private IdempotencyApplicationService service;

	@BeforeEach
	void setUp() {
		repository = mock(SpringDataIdempotencyKeyRepository.class);
		ObjectMapper mapper = JsonMapper.builder().build();
		service = new IdempotencyApplicationService(repository, mapper, CLOCK);
	}

	@Test
	void processingStatusThrowsInProgress() {
		String hash = service.hash(Map.of("x", 1));
		IdempotencyKeyEntity processing = IdempotencyKeyEntity.processing(
			"user1", "POST", "/test", "key-1", hash, NOW, NOW.plusSeconds(86400));

		when(repository.findByUserIdAndMethodAndRouteAndIdempotencyKey("user1", "POST", "/test", "key-1"))
			.thenReturn(Optional.of(processing));

		assertThatThrownBy(() -> service.execute("user1", "POST", "/test", "key-1", hash,
				() -> new CommandResponse(200, Map.of())))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_IN_PROGRESS"));
	}

	@Test
	void failedStatusThrowsFailed() {
		String hash = service.hash(Map.of("x", 1));
		IdempotencyKeyEntity failed = IdempotencyKeyEntity.processing(
			"user2", "POST", "/test", "key-2", hash, NOW, NOW.plusSeconds(86400));
		// Simula estado FAILED via reflection (nao ha metodo fail() na entity).
		try {
			var field = IdempotencyKeyEntity.class.getDeclaredField("status");
			field.setAccessible(true);
			field.set(failed, IdempotencyStatus.FAILED);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}

		when(repository.findByUserIdAndMethodAndRouteAndIdempotencyKey("user2", "POST", "/test", "key-2"))
			.thenReturn(Optional.of(failed));

		assertThatThrownBy(() -> service.execute("user2", "POST", "/test", "key-2", hash,
				() -> new CommandResponse(200, Map.of())))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_FAILED"));
	}

	@Test
	void concurrentInsertFallsBackToReplay() {
		String hash = service.hash(Map.of("data", "race"));

		// Primeira busca: nada encontrado (simula a janela de corrida).
		when(repository.findByUserIdAndMethodAndRouteAndIdempotencyKey("user3", "POST", "/test", "key-3"))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(completedEntity("user3", "POST", "/test", "key-3", hash, 200, "{\"ok\":true}")));

		// O saveAndFlush explode com constraint violation (o outro request ganhou).
		when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

		ResponseEntity<String> response = service.execute("user3", "POST", "/test", "key-3", hash,
			() -> { throw new AssertionError("action nao deveria executar"); });

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody()).contains("true");
	}

	@Test
	void concurrentInsertWithDifferentPayloadThrowsConflict() {
		String hashOriginal = service.hash(Map.of("amount", 10));
		String hashConcurrent = service.hash(Map.of("amount", 20));

		when(repository.findByUserIdAndMethodAndRouteAndIdempotencyKey("user4", "POST", "/test", "key-4"))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(completedEntity("user4", "POST", "/test", "key-4", hashOriginal, 200, "{}")));

		when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

		assertThatThrownBy(() -> service.execute("user4", "POST", "/test", "key-4", hashConcurrent,
				() -> new CommandResponse(200, Map.of())))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_PAYLOAD_CONFLICT"));
	}

	@Test
	void entityDefaultsWhenResponseFieldsNull() {
		IdempotencyKeyEntity entity = IdempotencyKeyEntity.processing(
			"user5", "POST", "/test", "key-5", "hash", NOW, NOW.plusSeconds(86400));

		// Antes de complete(): responseStatus e responseBody sao null.
		assertThat(entity.getResponseStatus()).isEqualTo(200);
		assertThat(entity.getResponseBody()).isEqualTo("{}");

		// Apos complete(): valores reais.
		entity.complete(201, "{\"id\":\"x\"}", NOW);
		assertThat(entity.getResponseStatus()).isEqualTo(201);
		assertThat(entity.getResponseBody()).isEqualTo("{\"id\":\"x\"}");
		assertThat(entity.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
	}

	private IdempotencyKeyEntity completedEntity(String userId, String method, String route,
			String key, String hash, int status, String body) {
		IdempotencyKeyEntity entity = IdempotencyKeyEntity.processing(userId, method, route, key, hash, NOW,
			NOW.plusSeconds(86400));
		entity.complete(status, body, NOW);
		return entity;
	}
}
