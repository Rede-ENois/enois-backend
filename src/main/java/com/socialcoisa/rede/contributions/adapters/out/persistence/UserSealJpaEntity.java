package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.contributions.domain.ContributionCategory;
import com.socialcoisa.rede.contributions.domain.SealStatus;
import com.socialcoisa.rede.contributions.domain.SealType;
import com.socialcoisa.rede.contributions.domain.UserSeal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_seals")
class UserSealJpaEntity {

	@Id
	private String id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "seal_type", nullable = false, length = 40)
	private SealType sealType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private ContributionCategory category;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SealStatus status;

	@Column(name = "granted_at", nullable = false)
	private Instant grantedAt;

	@Column(name = "granted_by")
	private String grantedBy;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "revoked_by")
	private String revokedBy;

	@Column(name = "revoke_reason", length = 1000)
	private String revokeReason;

	@Column(name = "evidence_count", nullable = false)
	private int evidenceCount;

	protected UserSealJpaEntity() {
	}

	static UserSealJpaEntity fromDomain(UserSeal seal) {
		UserSealJpaEntity e = new UserSealJpaEntity();
		e.id = seal.id();
		e.userId = seal.userId();
		e.sealType = seal.sealType();
		e.category = seal.category();
		e.status = seal.status();
		e.grantedAt = seal.grantedAt();
		e.grantedBy = seal.grantedBy();
		e.revokedAt = seal.revokedAt();
		e.revokedBy = seal.revokedBy();
		e.revokeReason = seal.revokeReason();
		e.evidenceCount = seal.evidenceCount();
		return e;
	}

	UserSeal toDomain() {
		return new UserSeal(id, userId, sealType, category, status, grantedAt, grantedBy, revokedAt, revokedBy,
			revokeReason, evidenceCount);
	}
}
