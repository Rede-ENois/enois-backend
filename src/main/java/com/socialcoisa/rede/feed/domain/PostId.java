package com.socialcoisa.rede.feed.domain;

public record PostId(String value) {

	public PostId {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("PostId vazio.");
		}
		value = value.trim();
	}
}
