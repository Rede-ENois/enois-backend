package com.socialcoisa.rede.studypairs.domain;

import java.time.Instant;

public record StudyPair(String id, String userA, String userB, String goalKey, String goalText, String topic,
		Status status, Instant startedAt, Instant endedAt) {

	public enum Status { ACTIVE, ENDED }

	public boolean isMember(String userId) {
		return userA.equals(userId) || userB.equals(userId);
	}

	public String partnerOf(String userId) {
		return userA.equals(userId) ? userB : userA;
	}
}
