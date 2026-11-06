package com.socialcoisa.rede.ops.monitoring;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Turns actuator health into monitoring events: one event per status change of the overall health
 * or of a component (db, diskSpace, ...), never one per check. Going DOWN or OUT_OF_SERVICE is
 * FATAL, recovering is INFO, UNKNOWN is WARN.
 *
 * <p>Runs on its own daemon thread, not on the shared scheduler (pool of one by default), so a slow
 * health indicator never delays the panel flush or the business jobs. The check uses the same
 * indicators as {@code /actuator/health}; the database one borrows a pool connection for a validity
 * check once per interval.</p>
 */
@Component
public class HealthWatcher {

	private static final Logger LOG = LoggerFactory.getLogger(HealthWatcher.class);

	private final ObjectProvider<HealthEndpoint> healthEndpoint;
	private final MonitoringService monitoring;
	private final Duration interval;
	private final boolean enabled;
	private final Map<String, String> lastStatus = new LinkedHashMap<>();
	private ScheduledExecutorService executor;

	HealthWatcher(ObjectProvider<HealthEndpoint> healthEndpoint, MonitoringService monitoring,
			@Value("${rede.monitoring.health-watch.interval:60s}") Duration interval,
			@Value("${rede.monitoring.health-watch.enabled:true}") boolean enabled) {
		this.healthEndpoint = healthEndpoint;
		this.monitoring = monitoring;
		this.interval = interval.isNegative() || interval.isZero() ? Duration.ofSeconds(60) : interval;
		this.enabled = enabled;
	}

	@EventListener(ApplicationReadyEvent.class)
	void start() {
		if (!enabled || healthEndpoint.getIfAvailable() == null || executor != null) {
			return;
		}
		executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, MonitoringLogAppender.INTERNAL_THREAD_PREFIX + "health");
			thread.setDaemon(true);
			return thread;
		});
		executor.scheduleWithFixedDelay(this::check, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
	}

	@PreDestroy
	void stop() {
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	void check() {
		try {
			HealthEndpoint endpoint = healthEndpoint.getIfAvailable();
			if (endpoint != null) {
				observe(flatten(endpoint.health()));
			}
		}
		catch (RuntimeException failure) {
			LOG.warn("health_watch_failed type={}", failure.getClass().getSimpleName());
		}
	}

	/** Compares against the previous snapshot and records one event per changed entry. */
	synchronized void observe(Map<String, String> current) {
		boolean first = lastStatus.isEmpty();
		for (Map.Entry<String, String> entry : current.entrySet()) {
			String before = lastStatus.put(entry.getKey(), entry.getValue());
			String now = entry.getValue();
			if (now.equals(before) || (first && Status.UP.getCode().equals(now))) {
				continue;
			}
			String level = isDown(now) ? "FATAL" : Status.UP.getCode().equals(now) ? "INFO" : "WARN";
			String change = before == null ? now : before + " -> " + now;
			monitoring.recordEvent(level, "HEALTH", "health " + entry.getKey() + " " + change,
				Map.of("component", entry.getKey(), "status", now));
		}
	}

	private static boolean isDown(String status) {
		return Status.DOWN.getCode().equals(status) || Status.OUT_OF_SERVICE.getCode().equals(status);
	}

	static Map<String, String> flatten(HealthDescriptor health) {
		Map<String, String> out = new TreeMap<>();
		out.put("overall", health.getStatus().getCode());
		if (health instanceof CompositeHealthDescriptor composite && composite.getComponents() != null) {
			composite.getComponents().forEach((name, child) -> out.put(name, child.getStatus().getCode()));
		}
		return out;
	}
}
