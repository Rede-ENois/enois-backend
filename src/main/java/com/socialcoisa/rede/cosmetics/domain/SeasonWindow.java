package com.socialcoisa.rede.cosmetics.domain;

import java.time.MonthDay;
import java.time.format.DateTimeFormatter;

/**
 * A yearly sale window as inclusive month-days ("10-15" to "11-02"). A window whose start comes
 * after its end wraps the year (Réveillon: "12-26" to "01-06").
 */
public record SeasonWindow(MonthDay start, MonthDay end) {

	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("MM-dd");

	public SeasonWindow {
		if (start == null || end == null) {
			throw new IllegalArgumentException("A season window needs a start and an end.");
		}
	}

	/** Parses "MM-dd" month-days; an invalid value fails (the catalog is validated at startup). */
	public static SeasonWindow parse(String start, String end) {
		return new SeasonWindow(MonthDay.parse("--" + start), MonthDay.parse("--" + end));
	}

	public boolean contains(MonthDay day) {
		if (!start.isAfter(end)) {
			return !day.isBefore(start) && !day.isAfter(end);
		}
		return !day.isBefore(start) || !day.isAfter(end);
	}

	public String startText() {
		return start.format(FORMAT);
	}

	public String endText() {
		return end.format(FORMAT);
	}
}
