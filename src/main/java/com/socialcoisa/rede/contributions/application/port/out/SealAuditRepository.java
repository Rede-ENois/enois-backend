package com.socialcoisa.rede.contributions.application.port.out;

import java.time.Instant;
import java.util.List;

public interface SealAuditRepository {

	void append(String userId, String sealType, String action, String actorUserId, String note, Instant at);

	List<Event> findByUser(String userId, int limit);

	record Event(String id, String userId, String sealType, String action, String actorUserId, String note,
			Instant createdAt) {
	}
}
