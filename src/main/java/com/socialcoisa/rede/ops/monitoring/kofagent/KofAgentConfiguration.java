package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.nio.file.Path;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.socialcoisa.rede.ops.monitoring.MonitoringService;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the Kof Agent channel as one more destination of {@link MonitoringService}.
 *
 * <p>The dispatcher is a {@link SmartLifecycle} in a very low phase: it starts before the web
 * server and stops after it, so errors from the last in-flight requests and the {@code app_stopping}
 * event still go out. Without a token and a chat id it stays inert (no thread, no listener).</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KofAgentProperties.class)
class KofAgentConfiguration {

	@Bean
	KofAgentDispatcher kofAgentDispatcher(KofAgentProperties properties, ObjectMapper mapper, Clock clock) {
		return new KofAgentDispatcher(properties, new TelegramBotTransport(properties, mapper),
			new AlertSpool(Path.of(properties.spoolDir()), properties.spoolMaxFiles(), mapper), clock);
	}

	@Bean
	KofAgentLifecycle kofAgentLifecycle(KofAgentProperties properties, KofAgentDispatcher dispatcher,
			MonitoringService monitoring, ObjectProvider<MeterRegistry> meters) {
		return new KofAgentLifecycle(properties, dispatcher, monitoring, meters);
	}

	static final class KofAgentLifecycle implements SmartLifecycle {

		private static final Logger LOG = LoggerFactory.getLogger(KofAgentLifecycle.class);

		private final KofAgentProperties properties;
		private final KofAgentDispatcher dispatcher;
		private final MonitoringService monitoring;
		private final ObjectProvider<MeterRegistry> meters;
		private volatile boolean running;

		KofAgentLifecycle(KofAgentProperties properties, KofAgentDispatcher dispatcher, MonitoringService monitoring,
				ObjectProvider<MeterRegistry> meters) {
			this.properties = properties;
			this.dispatcher = dispatcher;
			this.monitoring = monitoring;
			this.meters = meters;
		}

		@Override
		public void start() {
			running = true;
			if (!properties.configured()) {
				LOG.info("kof_agent_disabled enabled={} token={} chat={}", properties.enabled(),
					!properties.apiToken().isEmpty(), !properties.chatId().isEmpty());
				return;
			}
			dispatcher.activate();
			registerMetrics();
			monitoring.addListener(dispatcher);
			dispatcher.startThread();
		}

		@Override
		public void stop() {
			running = false;
			if (dispatcher.isActive()) {
				monitoring.removeListener(dispatcher);
				dispatcher.shutdown();
			}
		}

		@Override
		public boolean isRunning() {
			return running;
		}

		/** Starts before and stops after everything else (web server included). */
		@Override
		public int getPhase() {
			return Integer.MIN_VALUE + 1000;
		}

		private void registerMetrics() {
			MeterRegistry registry = meters.getIfAvailable();
			if (registry == null) {
				return;
			}
			String events = "rede.kof_agent.events";
			FunctionCounter.builder(events, dispatcher, d -> d.accepted.get()).tag("outcome", "accepted").register(registry);
			FunctionCounter.builder(events, dispatcher, d -> d.duplicates.get()).tag("outcome", "duplicate").register(registry);
			FunctionCounter.builder(events, dispatcher, d -> d.grouped.get()).tag("outcome", "grouped").register(registry);
			FunctionCounter.builder(events, dispatcher, d -> d.suppressed.get()).tag("outcome", "suppressed")
				.register(registry);
			FunctionCounter.builder(events, dispatcher, d -> d.countedOnly.get()).tag("outcome", "counted_only").register(registry);
			FunctionCounter.builder(events, dispatcher, KofAgentDispatcher::droppedTotal).tag("outcome", "dropped")
				.register(registry);
			String messages = "rede.kof_agent.messages";
			FunctionCounter.builder(messages, dispatcher, d -> d.bulletins.get()).tag("outcome", "bulletin")
				.register(registry);
			FunctionCounter.builder(messages, dispatcher, d -> d.digests.get()).tag("outcome", "digest").register(registry);
			FunctionCounter.builder(messages, dispatcher, d -> d.latencyAlerts.get()).tag("outcome", "latency")
				.register(registry);
			FunctionCounter.builder(messages, dispatcher, d -> d.sentMessages.get()).tag("outcome", "sent").register(registry);
			FunctionCounter.builder(messages, dispatcher, d -> d.failedAttempts.get()).tag("outcome", "failed")
				.register(registry);
			FunctionCounter.builder(messages, dispatcher, d -> d.replayed.get()).tag("outcome", "replayed")
				.register(registry);
			FunctionCounter.builder(messages, dispatcher, d -> d.deadLettered.get()).tag("outcome", "dead_letter")
				.register(registry);
			Gauge.builder("rede.kof_agent.queue", dispatcher, KofAgentDispatcher::queueSize).register(registry);
			Gauge.builder("rede.kof_agent.pending", dispatcher, KofAgentDispatcher::pendingMessages).register(registry);
			Gauge.builder("rede.kof_agent.spool", dispatcher, KofAgentDispatcher::spoolSize).register(registry);
			Gauge.builder("rede.kof_agent.circuit_open", dispatcher,
				d -> d.circuitState() == CircuitBreaker.State.CLOSED ? 0 : 1).register(registry);
		}
	}
}
