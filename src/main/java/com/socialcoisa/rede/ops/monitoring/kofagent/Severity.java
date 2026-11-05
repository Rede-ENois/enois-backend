package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.util.Locale;

/** Severity scale of the channel; logback has no FATAL, it comes from the classifier or a marker. */
public enum Severity {
	TRACE("🔵"), DEBUG("⚪"), INFO("🟢"), WARN("🟡"), ERROR("🟠"), FATAL("🔴");

	private final String icon;

	Severity(String icon) {
		this.icon = icon;
	}

	public String icon() {
		return icon;
	}

	public boolean atLeast(Severity other) {
		return ordinal() >= other.ordinal();
	}

	/** Unknown or missing levels count as INFO, the same rule the panel uses. */
	public static Severity of(String level) {
		if (level == null) {
			return INFO;
		}
		return switch (level.trim().toUpperCase(Locale.ROOT)) {
			case "TRACE" -> TRACE;
			case "DEBUG" -> DEBUG;
			case "WARN", "WARNING" -> WARN;
			case "ERROR" -> ERROR;
			case "FATAL" -> FATAL;
			default -> INFO;
		};
	}
}
