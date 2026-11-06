package com.socialcoisa.rede.shared.observability;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.ops.monitoring.MonitoringService;

/**
 * Duas linhas por ciclo de vida (subida e descida) com o que identifica o artefato: serviço,
 * perfil, versão e porta. No CloudWatch elas são o marco para correlacionar um deploy com o
 * comportamento seguinte — sem isso, "quando essa versão subiu?" só se responde no ArgoCD.
 *
 * <p>Both lines are also recorded as LIFECYCLE monitoring events. The stop event listens to
 * {@link ContextClosedEvent} (not {@code @PreDestroy}) because it is published before the lifecycle
 * beans stop, so the monitoring channels are still running when it arrives.</p>
 */
@Component
class StartupLogger {

	private static final Logger LOG = LoggerFactory.getLogger(StartupLogger.class);

	private final Environment environment;
	private final String version;
	private final MonitoringService monitoring;

	StartupLogger(Environment environment, ObjectProvider<BuildProperties> buildProperties,
			MonitoringService monitoring) {
		this.environment = environment;
		this.monitoring = monitoring;
		BuildProperties build = buildProperties.getIfAvailable();
		this.version = build != null ? build.getVersion() : "unknown";
	}

	@EventListener(ApplicationReadyEvent.class)
	void ready() {
		String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "unknown"));
		LOG.info("app_started service={} profile={} version={} port={}", service(), profiles(), version, port);
		record("app_started", Map.of("profile", profiles(), "version", version, "port", port));
	}

	@EventListener(ContextClosedEvent.class)
	void stopping() {
		LOG.info("app_stopping service={} profile={} version={}", service(), profiles(), version);
		record("app_stopping", Map.of("profile", profiles(), "version", version));
	}

	private void record(String message, Map<String, Object> metadata) {
		try {
			monitoring.recordEvent("INFO", "LIFECYCLE", message, metadata);
		}
		catch (RuntimeException ignored) {
			// Monitoring never blocks startup or shutdown.
		}
	}

	private String profiles() {
		return String.join(",", environment.getActiveProfiles());
	}

	private String service() {
		return environment.getProperty("spring.application.name", "rede");
	}
}
