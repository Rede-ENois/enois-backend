package com.socialcoisa.rede.idempotency.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.idempotency.adapters.out.persistence.IdempotencyKeyEntity;
import com.socialcoisa.rede.idempotency.adapters.out.persistence.SpringDataIdempotencyKeyRepository;
import com.socialcoisa.rede.shared.web.ApiException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class IdempotencyApplicationService {

	private final SpringDataIdempotencyKeyRepository repository;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public IdempotencyApplicationService(SpringDataIdempotencyKeyRepository repository, ObjectMapper objectMapper,
			Clock clock) {
		this.repository = repository;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Transactional
	public ResponseEntity<String> execute(UserId userId, String method, String route, String idempotencyKey,
			String requestHash, Supplier<CommandResponse> action) {
		return execute(userId.value(), method, route, idempotencyKey, requestHash, action);
	}

	@Transactional
	public ResponseEntity<String> execute(String userScope, String method, String route, String idempotencyKey,
			String requestHash, Supplier<CommandResponse> action) {
		if (!StringUtils.hasText(idempotencyKey)) {
			throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key obrigatoria.");
		}
		String key = idempotencyKey.trim();
		IdempotencyKeyEntity entity = repository
			.findByUserIdAndMethodAndRouteAndIdempotencyKey(userScope, method, route, key)
			.orElse(null);
		if (entity != null) {
			return replayOrReject(entity, requestHash);
		}
		IdempotencyKeyEntity created = IdempotencyKeyEntity.processing(userScope, method, route, key, requestHash,
			clock.instant(), clock.instant().plus(Duration.ofHours(24)));
		try {
			repository.saveAndFlush(created);
		}
		catch (DataIntegrityViolationException exception) {
			IdempotencyKeyEntity concurrent = repository
				.findByUserIdAndMethodAndRouteAndIdempotencyKey(userScope, method, route, key)
				.orElseThrow(() -> exception);
			return replayOrReject(concurrent, requestHash);
		}
		CommandResponse response = action.get();
		String body = writeJson(response.body());
		created.complete(response.status(), body, clock.instant());
		repository.save(created);
		return json(response.status(), body);
	}

	public String hash(Object value) {
		String payload = writeJson(value);
		byte[] digest;
		try {
			digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception exception) {
			throw new ApiException(500, "HASH_FAILURE", "Nao foi possivel calcular hash da requisicao.");
		}
		return HexFormat.of().formatHex(digest);
	}

	private ResponseEntity<String> replayOrReject(IdempotencyKeyEntity entity, String requestHash) {
		if (!entity.getRequestHash().equals(requestHash)) {
			throw new ApiException(409, "IDEMPOTENCY_PAYLOAD_CONFLICT",
				"Idempotency-Key reutilizada com payload diferente.");
		}
		if (entity.getStatus() == IdempotencyStatus.PROCESSING) {
			throw new ApiException(409, "IDEMPOTENCY_IN_PROGRESS", "Comando ainda em processamento.");
		}
		if (entity.getStatus() == IdempotencyStatus.FAILED) {
			throw new ApiException(409, "IDEMPOTENCY_FAILED", "Comando anterior falhou.");
		}
		return json(entity.getResponseStatus(), entity.getResponseBody());
	}

	private ResponseEntity<String> json(int status, String body) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JacksonException exception) {
			throw new ApiException(500, "JSON_SERIALIZATION_FAILURE", "Nao foi possivel serializar resposta.");
		}
	}
}
