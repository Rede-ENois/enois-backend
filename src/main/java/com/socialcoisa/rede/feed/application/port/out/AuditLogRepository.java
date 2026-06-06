package com.socialcoisa.rede.feed.application.port.out;

import com.socialcoisa.rede.feed.domain.UserId;

public interface AuditLogRepository {

	void record(UserId actorId, String action, String targetId, String metadata);
}
