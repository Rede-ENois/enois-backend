package com.socialcoisa.rede.studypairs.domain;

import java.time.Instant;

public record StudyPairRequest(String id, String userId, String goalKey, String goalText, String topic,
		Status status, Instant createdAt) {

	public enum Status { OPEN, MATCHED, CANCELLED }
}
