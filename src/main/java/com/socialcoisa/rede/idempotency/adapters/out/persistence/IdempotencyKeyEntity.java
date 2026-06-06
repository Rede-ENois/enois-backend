package com.socialcoisa.rede.idempotency.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.idempotency.application.IdempotencyStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "idempotency_keys",
		uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_key_scope",
				columnNames = { "user_id", "method", "route", "idempotency_key" }),
		indexes = @Index(name = "idx_idempotency_expires_at", columnList = "expires_at"))
public class IdempotencyKeyEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(nullable = false)
	private String method;

	@Column(nullable = false)
	private String route;

	@Column(name = "idempotency_key", nullable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", nullable = false)
	private String requestHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private IdempotencyStatus status;

	@Column(name = "response_status")
	private Integer responseStatus;

	@Lob
	@Column(name = "response_body")
	private String responseBody;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	protected IdempotencyKeyEntity() {
	}

	public static IdempotencyKeyEntity processing(String userId, String method, String route, String idempotencyKey,
			String requestHash, Instant createdAt, Instant expiresAt) {
		IdempotencyKeyEntity entity = new IdempotencyKeyEntity();
		entity.userId = userId;
		entity.method = method;
		entity.route = route;
		entity.idempotencyKey = idempotencyKey;
		entity.requestHash = requestHash;
		entity.status = IdempotencyStatus.PROCESSING;
		entity.createdAt = createdAt;
		entity.expiresAt = expiresAt;
		return entity;
	}

	public void complete(int responseStatus, String responseBody, Instant completedAt) {
		this.status = IdempotencyStatus.COMPLETED;
		this.responseStatus = responseStatus;
		this.responseBody = responseBody;
		this.completedAt = completedAt;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public IdempotencyStatus getStatus() {
		return status;
	}

	public int getResponseStatus() {
		return responseStatus == null ? 200 : responseStatus;
	}

	public String getResponseBody() {
		return responseBody == null ? "{}" : responseBody;
	}
}
