package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.socialcoisa.rede.ops.monitoring.MonitoringEvent;
import com.socialcoisa.rede.ops.monitoring.SensitiveDataRedactor;
import com.socialcoisa.rede.ops.monitoring.kofagent.Alert.Category;

/**
 * Decides what a monitoring event means for the channel: final severity (FATAL is derived here),
 * category, title, the redacted error summary and the fingerprint used to group repeats.
 *
 * <p>FATAL = the JVM itself failing ({@link VirtualMachineError}), the database unreachable
 * (no connection could be obtained), a health component going DOWN, or a log with the FATAL marker.</p>
 */
final class AlertClassifier {

	private static final String APP_PACKAGE = "com.socialcoisa.rede.";
	private static final int MAX_MESSAGE = 600;
	private static final int MAX_STACK = 1200;
	private static final Pattern NUMBER = Pattern.compile("\\b\\d+\\b");
	private static final Pattern HEX_ID = Pattern.compile("(?i)\\b[0-9a-f]{8}(?:-?[0-9a-f]{4}){3}-?[0-9a-f]{12}\\b|\\b[0-9a-f]{16,}\\b");
	private static final Pattern ID_SEGMENT = Pattern.compile("(?i)/(?:\\d+|[0-9a-f]{8}(?:-?[0-9a-f]{4}){3}-?[0-9a-f]{12}|[0-9a-f]{16,})(?=/|$)");

	private final long slowRequestMs;

	AlertClassifier(Duration slowRequest) {
		this.slowRequestMs = slowRequest.toMillis();
	}

	Alert classify(MonitoringEvent event) {
		Severity severity = Severity.of(event.level());
		String type = event.type() == null ? "SYSTEM" : event.type().toUpperCase(Locale.ROOT);
		String logger = event.logger() == null ? "" : event.logger();
		String message = clip(SensitiveDataRedactor.redactLine(event.message()), MAX_MESSAGE);
		Throwable failure = event.failure();
		Category category = category(type, logger, message, event.status(), failure);

		if (failure != null && chainHas(failure, VirtualMachineError.class)) {
			severity = Severity.FATAL;
			category = Category.INFRA;
		}
		else if (category == Category.DATABASE && isConnectionFailure(failure, message)) {
			severity = Severity.FATAL;
		}
		boolean slow = "REQUEST".equals(type) && event.status() != null && event.status() < 400
			&& event.durationMs() != null && event.durationMs() >= slowRequestMs;
		if (slow && !severity.atLeast(Severity.WARN)) {
			severity = Severity.WARN;
		}

		String route = event.route() != null && !"UNMATCHED".equals(event.route()) ? event.route() : maskIds(event.path());
		String endpoint = event.method() == null && route == null ? null
			: ((event.method() == null ? "" : event.method() + " ") + (route == null ? "" : route)).trim();
		String errorClass = errorClass(type, failure, message);
		String origin = failure == null ? null : origin(failure);
		String stack = failure == null ? null : stackSummary(failure);
		String title = title(type, category, event.status(), message, slow);
		boolean critical = severity.atLeast(Severity.ERROR) || category == Category.LIFECYCLE
			|| category == Category.HEALTH;
		String fingerprint = String.join("|", severity.name(), category.name(), type, nz(route),
			event.status() == null ? "" : event.status().toString(), nz(errorClass), nz(origin),
			"REQUEST".equals(type) ? "" : normalize(message));
		return new Alert(event.id(), event.createdAt(), severity, category, title, fingerprint, endpoint, event.status(),
			event.durationMs(), event.requestId(), message, errorClass, origin, stack, route, event.clientIp(),
			event.userId(), critical);
	}

	private static Category category(String type, String logger, String message, Integer status, Throwable failure) {
		switch (type) {
			case "LIFECYCLE":
				return Category.LIFECYCLE;
			case "HEALTH":
				return Category.HEALTH;
			case "AUDIT":
				return Category.AUDIT;
			default:
				break;
		}
		String text = (logger + " " + message + " " + chainNames(failure)).toLowerCase(Locale.ROOT);
		if (containsAny(text, "com.zaxxer.hikari", "org.hibernate", "org.flywaydb", "java.sql", "jdbc",
				"dataaccess", "transactionexception", "sqlexception", "deadlock", "lock wait timeout")) {
			return Category.DATABASE;
		}
		if (containsAny(text, "java.net.", "httptimeout", "connectexception", "unknownhost", "sslexception",
				"restclient", "software.amazon", "callnotpermitted", "webclient", "httpclient")) {
			return Category.EXTERNAL;
		}
		if (containsAny(text, "scheduling", "taskutils", "async", "uncaught_exception", "executor")) {
			return Category.ASYNC;
		}
		if (containsAny(text, "panic_mode", "feature_flag", "maintenance")) {
			return Category.STATE;
		}
		if ((status != null && (status == 401 || status == 403)) || containsAny(text, ".identity.", ".security.",
				"login", "auth_", "unauthorized", "forbidden", "abuse_guard")) {
			return Category.AUTH;
		}
		if (containsAny(text, "outofmemory", "no space left", "diskspace", "too many open files")) {
			return Category.INFRA;
		}
		if ("REQUEST".equals(type)) {
			return Category.HTTP;
		}
		return "EXCEPTION".equals(type) ? Category.EXCEPTION : Category.LOG;
	}

	private static String title(String type, Category category, Integer status, String message, boolean slow) {
		return switch (type) {
			case "REQUEST" -> slow ? "Requisição lenta" : "HTTP " + status;
			case "EXCEPTION" -> "HTTP " + (status == null ? 500 : status);
			case "LIFECYCLE" -> message.startsWith("app_started") ? "Serviço iniciado"
				: message.startsWith("app_stopping") ? "Serviço encerrando" : "Ciclo de vida";
			case "HEALTH" -> "Saúde do serviço";
			case "AUDIT" -> "Auditoria";
			default -> category == Category.LOG ? "Log" : category.label();
		};
	}

	private static String errorClass(String type, Throwable failure, String message) {
		if (failure != null) {
			return failure.getClass().getSimpleName();
		}
		if ("EXCEPTION".equals(type)) {
			int colon = message.indexOf(':');
			return colon > 0 ? message.substring(0, colon) : null;
		}
		return null;
	}

	/** First frame in application code ("UserService.java:142"); falls back to the top frame. */
	static String origin(Throwable failure) {
		StackTraceElement fallback = null;
		for (Throwable current : chain(failure)) {
			for (StackTraceElement frame : current.getStackTrace()) {
				if (fallback == null) {
					fallback = frame;
				}
				if (frame.getClassName().startsWith(APP_PACKAGE)) {
					return location(frame);
				}
			}
		}
		return fallback == null ? null : location(fallback);
	}

	private static String location(StackTraceElement frame) {
		String file = frame.getFileName() != null ? frame.getFileName() : frame.getClassName();
		return frame.getLineNumber() > 0 ? file + ":" + frame.getLineNumber() : file;
	}

	/** Each cause as "Class: message" plus up to three frames, preferring application code. */
	static String stackSummary(Throwable failure) {
		StringBuilder out = new StringBuilder();
		int causes = 0;
		for (Throwable current : chain(failure)) {
			if (causes++ == 3) {
				out.append("...\n");
				break;
			}
			if (out.length() > 0) {
				out.append("Caused by: ");
			}
			out.append(current.getClass().getName());
			if (current.getMessage() != null) {
				out.append(": ").append(clip(current.getMessage().replace('\n', ' '), 200));
			}
			out.append('\n');
			int printed = 0;
			for (StackTraceElement frame : current.getStackTrace()) {
				if (printed == 3) {
					break;
				}
				if (frame.getClassName().startsWith(APP_PACKAGE) || (printed == 0 && current == failure)) {
					out.append("  at ").append(frame).append('\n');
					printed++;
				}
			}
		}
		return clip(SensitiveDataRedactor.redact(out.toString().trim()), MAX_STACK);
	}

	private static boolean isConnectionFailure(Throwable failure, String message) {
		String text = (chainNames(failure) + " " + message).toLowerCase(Locale.ROOT);
		return containsAny(text, "cannotgetjdbcconnection", "sqltransientconnection", "sqlnontransientconnection",
			"cannotcreatetransaction", "connection is not available", "communications link failure",
			"connection refused");
	}

	private static Iterable<Throwable> chain(Throwable failure) {
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		java.util.List<Throwable> out = new java.util.ArrayList<>();
		for (Throwable current = failure; current != null && seen.add(current) && out.size() < 10;
				current = current.getCause()) {
			out.add(current);
		}
		return out;
	}

	private static boolean chainHas(Throwable failure, Class<? extends Throwable> type) {
		for (Throwable current : chain(failure)) {
			if (type.isInstance(current)) {
				return true;
			}
		}
		return false;
	}

	private static String chainNames(Throwable failure) {
		if (failure == null) {
			return "";
		}
		StringBuilder names = new StringBuilder();
		for (Throwable current : chain(failure)) {
			names.append(current.getClass().getName()).append(' ');
			if (current.getMessage() != null) {
				names.append(clip(current.getMessage(), 200)).append(' ');
			}
		}
		return names.toString();
	}

	static String maskIds(String path) {
		return path == null ? null : ID_SEGMENT.matcher(path).replaceAll("/{id}");
	}

	private static String normalize(String message) {
		String value = HEX_ID.matcher(message).replaceAll("*");
		return clip(NUMBER.matcher(value).replaceAll("#"), 200);
	}

	private static boolean containsAny(String text, String... needles) {
		for (String needle : needles) {
			if (text.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	static String clip(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max - 1) + "…";
	}

	private static String nz(String value) {
		return value == null ? "" : value;
	}
}
