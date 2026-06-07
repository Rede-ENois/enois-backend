package com.socialcoisa.rede.shared.audit;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_logs", indexes = @Index(name = "idx_audit_actor_created", columnList = "actor_id, created_at"))
class AuditLogJpaEntity {

	@Id
	private String id;

	@Column(name = "actor_id", nullable = false)
	private String actorId;

	@Column(nullable = false)
	private String action;

	@Column(name = "target_id", nullable = false)
	private String targetId;

	@Column(length = 10000)
	private String metadata;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AuditLogJpaEntity() {
	}

	AuditLogJpaEntity(String id, String actorId, String action, String targetId, String metadata, Instant createdAt) {
		this.id = id;
		this.actorId = actorId;
		this.action = action;
		this.targetId = targetId;
		this.metadata = metadata;
		this.createdAt = createdAt;
	}
}
