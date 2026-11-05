package com.socialcoisa.rede.ops.monitoring;

import java.time.Instant;

/**
 * One observability event as it enters {@link MonitoringService}, handed to every
 * {@link MonitoringEventListener} before the panel's own per-minute cap is applied.
 *
 * <p>{@code message} is already redacted. {@code failure} is the live throwable (when there is one)
 * so a listener can build its own summary; listeners must redact anything they derive from it.
 * {@code clientIp} and {@code userId} identify who triggered the event (the maintainers want them in
 * the private ops channel); {@code deviceId} stays in the panel only.</p>
 *
 * @param level TRACE, DEBUG, INFO, WARN, ERROR or FATAL
 * @param type REQUEST, EXCEPTION, LOG, AUDIT, LIFECYCLE, HEALTH, SYSTEM or a caller-defined type
 * @param logger source logger for LOG/AUDIT events, {@code null} otherwise
 */
public record MonitoringEvent(String id, Instant createdAt, String level, String type, String message,
		String requestId, String method, String path, String route, Integer status, Long durationMs,
		String clientIp, String userId, String logger, Throwable failure) {
}
