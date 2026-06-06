package com.socialcoisa.rede.shared.web;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CursorCodec {

	public Optional<Instant> decode(String cursor) {
		if (!StringUtils.hasText(cursor)) {
			return Optional.empty();
		}
		try {
			return Optional.of(Instant.parse(cursor));
		}
		catch (RuntimeException exception) {
			throw new ApiException(400, "INVALID_CURSOR", "Cursor invalido.");
		}
	}

	public String encode(Instant instant) {
		return instant == null ? null : instant.toString();
	}
}
