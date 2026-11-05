package com.socialcoisa.rede.ops.monitoring;

import java.util.List;

import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Captura os logs da aplicação para o painel admin e para os outros destinos de monitoração.
 *
 * <p>Fica no root logger por código, sem alterar o logback-spring.xml: o app continua mandando
 * tudo para o stdout/CloudWatch normalmente. O appender não pode logar nem capturar os próprios
 * logs — isso criaria um loop de escrita.</p>
 *
 * <p>Painel: logs WARN/ERROR da aplicação e o logger de auditoria, como sempre. Listeners (canal do
 * Telegram): qualquer nível que o logback emitir e que o listener aceitar, mais WARN/ERROR de
 * bibliotecas (pool do banco, Hibernate, agendador, Tomcat), que é onde aparecem falhas de banco e
 * de infraestrutura. Um log com o marcador {@code FATAL} sobe para o nível FATAL.</p>
 */
@Component
public class MonitoringLogAppender extends AppenderBase<ILoggingEvent> {

	private static final String APP_PREFIX = "com.socialcoisa.rede";
	private static final String MONITORING_PREFIX = "com.socialcoisa.rede.ops.monitoring";
	/** Threads of the monitoring pipeline itself (dispatcher, health watcher): never captured. */
	public static final String INTERNAL_THREAD_PREFIX = "rede-monitoring-";
	private static final ThreadLocal<Boolean> IN_APPENDER = new ThreadLocal<>();

	private final MonitoringService monitoring;
	private ch.qos.logback.classic.Logger attachedTo;

	MonitoringLogAppender(MonitoringService monitoring) {
		this.monitoring = monitoring;
	}

	@PostConstruct
	void attach() {
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		setContext(context);
		setName("monitoring-panel");
		start();
		attachedTo = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
		attachedTo.addAppender(this);
	}

	@PreDestroy
	void detach() {
		if (attachedTo != null) {
			attachedTo.detachAppender(this);
		}
		stop();
	}

	@Override
	protected void append(ILoggingEvent event) {
		if (IN_APPENDER.get() != null || event == null) {
			return;
		}
		String logger = event.getLoggerName();
		if (logger == null || logger.startsWith(MONITORING_PREFIX)
				|| logger.startsWith("com.socialcoisa.rede.shared.web.RequestObservabilityFilter")
				|| logger.startsWith("com.socialcoisa.rede.shared.web.GlobalExceptionHandler")
				|| logger.startsWith("com.socialcoisa.rede.shared.observability.StartupLogger")
				|| Thread.currentThread().getName().startsWith(INTERNAL_THREAD_PREFIX)) {
			return;
		}
		boolean audit = "audit".equals(logger);
		boolean appLog = logger.startsWith(APP_PREFIX);
		boolean warnOrAbove = event.getLevel().isGreaterOrEqual(Level.WARN);
		if (!appLog && !audit && !warnOrAbove) {
			return;
		}
		String level = levelOf(event);
		boolean panel = (appLog && warnOrAbove) || audit;
		if (!panel && !monitoring.listenersAccept(level, audit ? "AUDIT" : "LOG")) {
			return;
		}
		IN_APPENDER.set(Boolean.TRUE);
		try {
			monitoring.recordLog(level, logger, event.getFormattedMessage(), MDC.get("requestId"), MDC.get("userId"),
				MDC.get("http.path"), throwableOf(event), panel);
		}
		catch (RuntimeException ignored) {
			// Log não pode derrubar a thread que estava logando.
		}
		finally {
			IN_APPENDER.remove();
		}
	}

	private static String levelOf(ILoggingEvent event) {
		List<Marker> markers = event.getMarkerList();
		if (markers != null) {
			for (Marker marker : markers) {
				if ("FATAL".equals(marker.getName())) {
					return "FATAL";
				}
			}
		}
		return event.getLevel().toString();
	}

	private static Throwable throwableOf(ILoggingEvent event) {
		return event.getThrowableProxy() instanceof ThrowableProxy proxy ? proxy.getThrowable() : null;
	}
}
