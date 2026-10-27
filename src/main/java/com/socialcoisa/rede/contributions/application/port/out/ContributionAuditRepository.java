package com.socialcoisa.rede.contributions.application.port.out;

import java.time.Instant;
import java.util.List;

public interface ContributionAuditRepository {

	void append(String contributionId, String actorUserId, String action, String fromStatus, String toStatus,
			String note, Instant at);

	List<AuditEvent> findByContribution(String contributionId, int limit);

	record AuditEvent(String id, String contributionId, String actorUserId, String action, String fromStatus,
			String toStatus, String note, Instant createdAt) {
	}
}
