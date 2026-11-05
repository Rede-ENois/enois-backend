package com.socialcoisa.rede.ops.monitoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Health becomes one event per status change, never one per check. */
class HealthWatcherTests {

	private final MonitoringService monitoring = mock(MonitoringService.class);

	@SuppressWarnings("unchecked")
	private final HealthWatcher watcher = new HealthWatcher(mock(ObjectProvider.class), monitoring,
		Duration.ofSeconds(60), true);

	@Test
	void reportsTransitionsOnly() {
		watcher.observe(Map.of("overall", "UP", "db", "UP"));
		verify(monitoring, never()).recordEvent(any(), any(), any(), any());

		watcher.observe(Map.of("overall", "DOWN", "db", "DOWN"));
		verify(monitoring).recordEvent(eq("FATAL"), eq("HEALTH"), eq("health db UP -> DOWN"), any());
		verify(monitoring).recordEvent(eq("FATAL"), eq("HEALTH"), eq("health overall UP -> DOWN"), any());

		watcher.observe(Map.of("overall", "DOWN", "db", "DOWN"));
		watcher.observe(Map.of("overall", "UP", "db", "UP"));
		verify(monitoring).recordEvent(eq("INFO"), eq("HEALTH"), eq("health db DOWN -> UP"), any());
	}

	@Test
	void aComponentAlreadyDownAtStartupIsReported() {
		watcher.observe(Map.of("overall", "DOWN", "diskSpace", "DOWN"));
		verify(monitoring).recordEvent(eq("FATAL"), eq("HEALTH"), eq("health diskSpace DOWN"), any());
	}
}
