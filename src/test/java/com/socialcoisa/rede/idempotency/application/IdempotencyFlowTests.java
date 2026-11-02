package com.socialcoisa.rede.idempotency.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Testa o contrato da idempotencia contra o banco real (H2): mesma key = mesma resposta,
 * action nunca executa duas vezes, payload diferente rejeita, e key ausente explode.
 *
 * Prefixo "idemp_flow_" em todos os valores para nao colidir com outros testes no H2 compartilhado.
 */
@SpringBootTest
class IdempotencyFlowTests {

	@Autowired
	private IdempotencyApplicationService idempotency;

	@Test
	void firstExecutionRunsActionAndReturnsResult() {
		var counter = new AtomicInteger();

		ResponseEntity<String> response = idempotency.execute(
			"idemp_flow_user1", "POST", "/api/test/first", "key-first-1",
			idempotency.hash(Map.of("data", "a")),
			() -> { counter.incrementAndGet(); return new CommandResponse(201, Map.of("id", "abc")); });

		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(response.getBody()).contains("\"id\"");
		assertThat(response.getBody()).contains("abc");
		assertThat(counter.get()).isEqualTo(1);
	}

	@Test
	void replayWithSameHashReturnsSameResponseWithoutRunningAction() {
		var counter = new AtomicInteger();
		String hash = idempotency.hash(Map.of("data", "replay"));

		idempotency.execute("idemp_flow_user2", "POST", "/api/test/replay", "key-replay-1", hash,
			() -> { counter.incrementAndGet(); return new CommandResponse(200, Map.of("ok", true)); });

		ResponseEntity<String> replayed = idempotency.execute(
			"idemp_flow_user2", "POST", "/api/test/replay", "key-replay-1", hash,
			() -> { counter.incrementAndGet(); return new CommandResponse(200, Map.of("ok", true)); });

		assertThat(replayed.getStatusCode().value()).isEqualTo(200);
		assertThat(replayed.getBody()).contains("true");
		assertThat(counter.get()).isEqualTo(1);
	}

	@Test
	void sameKeyDifferentPayloadThrowsConflict() {
		String hashA = idempotency.hash(Map.of("amount", 10));
		String hashB = idempotency.hash(Map.of("amount", 20));

		idempotency.execute("idemp_flow_user3", "POST", "/api/test/conflict", "key-conflict-1", hashA,
			() -> new CommandResponse(200, Map.of("ok", true)));

		assertThatThrownBy(() -> idempotency.execute(
				"idemp_flow_user3", "POST", "/api/test/conflict", "key-conflict-1", hashB,
				() -> new CommandResponse(200, Map.of("ok", true))))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_PAYLOAD_CONFLICT"));
	}

	@Test
	void missingKeyThrows400() {
		assertThatThrownBy(() -> idempotency.execute(
				"idemp_flow_user4", "POST", "/api/test/nokey", null, "somehash",
				() -> new CommandResponse(200, Map.of())))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED"));

		assertThatThrownBy(() -> idempotency.execute(
				"idemp_flow_user4", "POST", "/api/test/nokey", "  ", "somehash",
				() -> new CommandResponse(200, Map.of())))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED"));
	}

	@Test
	void sameKeyDifferentUserExecutesIndependently() {
		var counter = new AtomicInteger();
		String hash = idempotency.hash(Map.of("data", "scoped"));

		idempotency.execute("idemp_flow_userA", "POST", "/api/test/scope", "key-scope-1", hash,
			() -> { counter.incrementAndGet(); return new CommandResponse(200, Map.of("who", "A")); });

		idempotency.execute("idemp_flow_userB", "POST", "/api/test/scope", "key-scope-1", hash,
			() -> { counter.incrementAndGet(); return new CommandResponse(200, Map.of("who", "B")); });

		assertThat(counter.get()).isEqualTo(2);
	}

	@Test
	void sameKeyDifferentRouteExecutesIndependently() {
		var counter = new AtomicInteger();
		String hash = idempotency.hash(Map.of("data", "route"));

		idempotency.execute("idemp_flow_user5", "POST", "/api/test/route-a", "key-route-1", hash,
			() -> { counter.incrementAndGet(); return new CommandResponse(200, Map.of("r", "a")); });

		idempotency.execute("idemp_flow_user5", "POST", "/api/test/route-b", "key-route-1", hash,
			() -> { counter.incrementAndGet(); return new CommandResponse(200, Map.of("r", "b")); });

		assertThat(counter.get()).isEqualTo(2);
	}

	@Test
	void hashIsDeterministicAndSensitive() {
		var input = Map.of("amount", 100, "to", "someone");
		assertThat(idempotency.hash(input)).isEqualTo(idempotency.hash(input));
		assertThat(idempotency.hash(input)).isNotEqualTo(idempotency.hash(Map.of("amount", 999, "to", "someone")));
	}
}
