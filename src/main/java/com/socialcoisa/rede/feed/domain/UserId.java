package com.socialcoisa.rede.feed.domain;

public record UserId(String value) {

	public UserId {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("UserId vazio.");
		}
		value = value.trim();
	}
}
