package com.socialcoisa.rede.studypairs.domain;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.Locale;

/**
 * The two keys the module matches on.
 *
 * The goal key folds case and accents ("Aprender Rust" and "aprender rúst" become
 * "aprender-rust"), so people who wrote the same goal differently still find each other. The week
 * key is the ISO week ("2026-W44"); the caller picks the date in America/Sao_Paulo.
 */
public final class StudyGoal {

	public static final int MAX_LENGTH = 120;

	private StudyGoal() {
	}

	/** Normalized slug of the goal; empty when the text has no letter or digit. */
	public static String key(String goal) {
		String slug = Normalizer.normalize(goal == null ? "" : goal, Normalizer.Form.NFD)
			.replaceAll("\\p{M}+", "")
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		return slug.length() > MAX_LENGTH ? slug.substring(0, MAX_LENGTH).replaceAll("-+$", "") : slug;
	}

	public static String weekKey(LocalDate date) {
		return String.format("%d-W%02d", date.get(IsoFields.WEEK_BASED_YEAR),
			date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
	}
}
