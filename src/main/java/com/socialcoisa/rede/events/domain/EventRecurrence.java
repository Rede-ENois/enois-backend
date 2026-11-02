package com.socialcoisa.rede.events.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/** How a recurring event ("ritual") repeats. NONE is a one-off event. */
public enum EventRecurrence {

	NONE, WEEKLY, BIWEEKLY, MONTHLY;

	/**
	 * Date of the occurrence after {@code current}.
	 *
	 * Monthly counts from the series anchor (the first occurrence) so the day of month does not drift:
	 * a ritual on the 31st falls on the last day of shorter months and returns to the 31st after them.
	 */
	public LocalDate next(LocalDate anchor, LocalDate current) {
		return switch (this) {
			case NONE -> throw new IllegalStateException("A one-off event has no next occurrence");
			case WEEKLY -> current.plusWeeks(1);
			case BIWEEKLY -> current.plusWeeks(2);
			case MONTHLY -> anchor.plusMonths(ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(current)) + 1);
		};
	}

}
