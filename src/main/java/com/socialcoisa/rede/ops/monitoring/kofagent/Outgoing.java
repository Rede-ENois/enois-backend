package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.time.Instant;

/**
 * A formatted message waiting to be sent.
 *
 * @param spoolKey file in the spool when the message is critical and was persisted, else {@code null}
 * @param events how many events the message represents (1 for a single alert, N for a repeat summary)
 */
record Outgoing(String spoolKey, Severity severity, String text, boolean critical, boolean silent,
		Instant createdAt, long events) {

	Outgoing withSpoolKey(String key) {
		return new Outgoing(key, severity, text, critical, silent, createdAt, events);
	}
}
