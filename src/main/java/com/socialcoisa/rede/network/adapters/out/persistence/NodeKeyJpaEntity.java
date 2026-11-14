package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "network_node_keys")
class NodeKeyJpaEntity {

	@Id
	@Column(length = 40)
	private String id;

	@Column(name = "node_id", nullable = false, length = 40)
	private String nodeId;

	@Column(name = "key_hash", nullable = false, length = 64)
	private String keyHash;

	@Column(name = "key_prefix", nullable = false, length = 16)
	private String keyPrefix;

	@Column(name = "created_by", nullable = false, length = 40)
	private String createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_used_at")
	private Instant lastUsedAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "revoked_by", length = 40)
	private String revokedBy;

	protected NodeKeyJpaEntity() {
	}

	NodeKeyJpaEntity(String id, String nodeId, String keyHash, String keyPrefix, String createdBy, Instant createdAt) {
		this.id = id;
		this.nodeId = nodeId;
		this.keyHash = keyHash;
		this.keyPrefix = keyPrefix;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
	}

	String id() {
		return id;
	}

	String nodeId() {
		return nodeId;
	}

	String keyHash() {
		return keyHash;
	}

	String keyPrefix() {
		return keyPrefix;
	}

	Instant createdAt() {
		return createdAt;
	}

	Instant lastUsedAt() {
		return lastUsedAt;
	}

	Instant revokedAt() {
		return revokedAt;
	}
}
