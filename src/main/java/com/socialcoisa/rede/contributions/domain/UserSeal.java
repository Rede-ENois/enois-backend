package com.socialcoisa.rede.contributions.domain;

import java.time.Instant;

/** Selo concedido a uma conta; uma linha por (user, sealType), com o histórico nos eventos. */
public record UserSeal(String id, String userId, SealType sealType, ContributionCategory category,
		SealStatus status, Instant grantedAt, String grantedBy, Instant revokedAt, String revokedBy,
		String revokeReason, int evidenceCount) {

	public UserSeal revoke(Instant at, String by, String reason) {
		return new UserSeal(id, userId, sealType, category, SealStatus.REVOKED, grantedAt, grantedBy, at, by,
			reason, evidenceCount);
	}

	public UserSeal regrant(Instant at, String by, int evidence) {
		return new UserSeal(id, userId, sealType, category, SealStatus.ACTIVE, at, by, null, null, null, evidence);
	}
}
