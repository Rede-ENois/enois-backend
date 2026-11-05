package com.socialcoisa.rede.shared.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * A thread that dies from an uncaught exception (outside the HTTP handler, the scheduler and
 * {@code @Async}, which log their own failures) is otherwise only printed to stderr by the JVM.
 * This handler turns it into an ERROR log line, so it reaches CloudWatch and the monitoring channels.
 */
@Component
class UncaughtExceptionReporter {

	private static final Logger LOG = LoggerFactory.getLogger(UncaughtExceptionReporter.class);

	private Thread.UncaughtExceptionHandler previous;

	@PostConstruct
	void install() {
		previous = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler(this::report);
	}

	@PreDestroy
	void uninstall() {
		Thread.setDefaultUncaughtExceptionHandler(previous);
	}

	void report(Thread thread, Throwable failure) {
		try {
			LOG.error("uncaught_exception thread={}", thread.getName(), failure);
		}
		catch (RuntimeException ignored) {
			// Nothing else to do: the thread is already dying.
		}
		if (previous != null) {
			previous.uncaughtException(thread, failure);
		}
	}
}
