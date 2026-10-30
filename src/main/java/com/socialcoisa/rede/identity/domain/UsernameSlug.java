package com.socialcoisa.rede.identity.domain;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The @ suggested at signup from the name (or the e-mail local part). An accent becomes the plain
 * letter ("Cecília" → "cecilia") instead of a hyphen ("cec-lia"); anything else that is not a letter
 * or a digit becomes a hyphen, and a value with nothing left falls back to "user".
 */
public final class UsernameSlug {

	private static final String FALLBACK = "user";

	private UsernameSlug() {
	}

	public static String from(String value) {
		String slug = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
			.replaceAll("\\p{M}+", "")
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		return slug.isEmpty() ? FALLBACK : slug;
	}
}
