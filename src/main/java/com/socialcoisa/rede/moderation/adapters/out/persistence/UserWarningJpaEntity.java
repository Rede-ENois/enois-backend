package com.socialcoisa.rede.moderation.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.moderation.domain.UserWarning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_warnings", indexes = @Index(name = "idx_user_warnings_user", columnList = "user_id, created_at"))
class UserWarningJpaEntity {

	@Id
	private String id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "admin_id", nullable = false)
	private String adminId;

	@Column(nullable = false, length = 2000)
	private String reason;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "revoked_by")
	private String revokedBy;

	protected UserWarningJpaEntity() {
	}

	static UserWarningJpaEntity fromDomain(UserWarning warning) {
		UserWarningJpaEntity e = new UserWarningJpaEntity();
		e.id = warning.id();
		e.userId = warning.userId();
		e.adminId = warning.adminId();
		e.reason = warning.reason();
		e.createdAt = warning.createdAt();
		e.revokedAt = warning.revokedAt();
		e.revokedBy = warning.revokedBy();
		return e;
	}

	UserWarning toDomain() {
		return UserWarning.restore(id, userId, adminId, reason, createdAt, revokedAt, revokedBy);
	}
}