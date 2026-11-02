package com.socialcoisa.rede.feed.application;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import com.socialcoisa.rede.shared.web.ApiException;

public record TrendingCursor(Instant windowEnd, int reactions, Instant createdAt, String postId) {

	public String encode() {
		String value = "1|" + windowEnd + "|" + reactions + "|" + createdAt + "|" + postId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	public static TrendingCursor decode(String value) {
		if (value == null || value.isBlank()) return null;
		try {
			String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
			if (parts.length != 5 || !parts[0].equals("1")) throw new IllegalArgumentException();
			TrendingCursor cursor = new TrendingCursor(Instant.parse(parts[1]), Integer.parseInt(parts[2]),
				Instant.parse(parts[3]), parts[4]);
			if (cursor.reactions < 0 || cursor.postId.isBlank() || cursor.createdAt.isAfter(cursor.windowEnd)
					|| cursor.createdAt.isBefore(cursor.windowEnd.minus(Duration.ofHours(24)))) {
				throw new IllegalArgumentException();
			}
			return cursor;
		}
		catch (IllegalArgumentException | java.time.DateTimeException exception) {
			throw new ApiException(400, "INVALID_CURSOR", "Cursor invalido.");
		}
	}
}
