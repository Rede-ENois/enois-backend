package com.socialcoisa.rede.studypairs.domain;

import java.time.Instant;

public record StudyCheckin(String pairId, String userId, String weekKey, String note, Instant createdAt,
		Instant updatedAt) {
}
