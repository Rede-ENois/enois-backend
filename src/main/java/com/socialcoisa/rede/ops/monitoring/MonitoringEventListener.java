package com.socialcoisa.rede.ops.monitoring;

/**
 * Extra destination for monitoring events (the panel file is the built-in one).
 *
 * <p>{@link #onEvent} runs on the thread that produced the event, often a request thread, so an
 * implementation must only hand the event off (for example to a bounded queue) and return: no I/O,
 * no locks held across calls, no logging through the application loggers.</p>
 */
public interface MonitoringEventListener {

	/**
	 * Cheap pre-check so callers can skip building events nobody wants (e.g. INFO log lines).
	 *
	 * @param type REQUEST, EXCEPTION, LOG, AUDIT, LIFECYCLE, HEALTH...
	 */
	default boolean accepts(String level, String type) {
		return true;
	}

	void onEvent(MonitoringEvent event);
}
