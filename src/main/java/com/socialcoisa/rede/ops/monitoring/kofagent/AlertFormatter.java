package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renders one bulletin (everything collected in one flush window) as Telegram HTML.
 *
 * <p>Layout: a header with service, environment, window and counts per severity; one block per
 * distinct entry (repeats collapsed into "×N"), most severe first, with endpoint, IP, user, request
 * id, error, origin and, for ERROR/FATAL, a short stack; a footer with request counts and the
 * channel's own health. Text shown in the channel is pt-BR copy for the team; every interpolated
 * value is HTML-escaped, so an event can never inject markup. A bulletin larger than Telegram's
 * limit is split into numbered parts on entry boundaries.</p>
 */
final class AlertFormatter {

	static final int MAX_MESSAGE = 3800;
	static final String SEPARATOR = "\n\n";
	private static final int MAX_LISTED = 3;

	private final String serviceName;
	private final String environmentLabel;
	private final String environmentTag;
	private final DateTimeFormatter day;
	private final DateTimeFormatter clock;

	AlertFormatter(String serviceName, String environment, ZoneId zone) {
		this.serviceName = serviceName;
		String key = environment.trim().toLowerCase(Locale.ROOT);
		this.environmentLabel = switch (key) {
			case "prd", "prod", "production", "producao", "produção" -> "Produção";
			case "qa" -> "QA";
			case "sandbox" -> "Sandbox";
			case "hml", "staging", "homologacao", "homologação" -> "Homologação";
			case "dev", "local" -> "Desenvolvimento";
			default -> environment.trim();
		};
		this.environmentTag = switch (key) {
			case "prd", "prod", "production", "producao", "produção" -> "PRD";
			default -> environment.trim().toUpperCase(Locale.ROOT);
		};
		this.day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone);
		this.clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone);
	}

	/** One or more Telegram messages for the window, each under {@link #MAX_MESSAGE} characters. */
	List<String> format(Bulletin bulletin) {
		List<String> blocks = new ArrayList<>();
		for (Entry entry : bulletin.entries()) {
			blocks.add(entry(entry));
		}
		String footer = footer(bulletin);
		List<List<String>> parts = new ArrayList<>();
		List<String> current = new ArrayList<>();
		int length = header(bulletin, 1, 1).length() + footer.length();
		for (String block : blocks) {
			if (!current.isEmpty() && length + SEPARATOR.length() + block.length() > MAX_MESSAGE) {
				parts.add(current);
				current = new ArrayList<>();
				length = header(bulletin, 2, 2).length() + footer.length();
			}
			current.add(block);
			length += SEPARATOR.length() + block.length();
		}
		parts.add(current);
		List<String> messages = new ArrayList<>();
		for (int i = 0; i < parts.size(); i++) {
			StringBuilder out = new StringBuilder(header(bulletin, i + 1, parts.size()));
			for (String block : parts.get(i)) {
				out.append(SEPARATOR).append(block);
			}
			out.append(SEPARATOR).append(footer);
			messages.add(clip(out.toString()));
		}
		return messages;
	}

	private String header(Bulletin bulletin, int part, int parts) {
		Map<Severity, Long> counts = new EnumMap<>(Severity.class);
		for (Entry entry : bulletin.entries()) {
			counts.merge(entry.alert().severity(), entry.count(), Long::sum);
		}
		StringBuilder out = new StringBuilder("📡 <b>").append(escape(serviceName)).append("</b> · ")
			.append(escape(environmentLabel)).append(" · <code>").append(escape(environmentTag)).append("</code>");
		if (parts > 1) {
			out.append(" · parte ").append(part).append('/').append(parts);
		}
		out.append('\n').append(day.format(bulletin.from())).append(' ').append(clock.format(bulletin.from()))
			.append(" a ").append(clock.format(bulletin.to()));
		StringBuilder levels = new StringBuilder();
		for (int i = Severity.values().length - 1; i >= 0; i--) {
			Severity severity = Severity.values()[i];
			Long count = counts.get(severity);
			if (count != null) {
				levels.append(levels.length() == 0 ? "" : " · ").append(severity.icon()).append(' ')
					.append(severity.name()).append(' ').append(count);
			}
		}
		if (levels.length() > 0) {
			out.append('\n').append(levels);
		}
		return out.toString();
	}

	private String entry(Entry entry) {
		Alert alert = entry.alert();
		StringBuilder out = new StringBuilder().append(alert.severity().icon()).append(" <b>")
			.append(alert.severity().name()).append("</b> ").append(clock.format(alert.at())).append(" · ")
			.append(escape(alert.title()));
		if (alert.category() != Alert.Category.HTTP && alert.category() != Alert.Category.LOG
				&& !alert.title().equals(alert.category().label())) {
			out.append(" · ").append(escape(alert.category().label()));
		}
		if (entry.count() > 1) {
			out.append(" <b>×").append(entry.count()).append("</b> (até ").append(clock.format(entry.lastAt()))
				.append(')');
		}
		List<String> request = new ArrayList<>();
		if (alert.endpoint() != null && !alert.endpoint().isBlank()) {
			request.add("<code>" + escape(alert.endpoint()) + "</code>");
		}
		if (alert.status() != null) {
			request.add("status " + alert.status());
		}
		if (alert.durationMs() != null) {
			request.add(alert.durationMs() + " ms");
		}
		line(out, request);
		List<String> who = new ArrayList<>();
		if (!entry.ips().isEmpty()) {
			who.add("IP " + listed(entry.ips(), entry.distinctIps()));
		}
		if (!entry.users().isEmpty()) {
			who.add("user " + listed(entry.users(), entry.distinctUsers()));
		}
		if (alert.requestId() != null) {
			who.add("req <code>" + escape(alert.requestId()) + "</code>");
		}
		line(out, who);
		if (alert.errorClass() != null || alert.origin() != null) {
			out.append('\n').append(escape(alert.errorClass() == null ? "" : alert.errorClass()));
			if (alert.origin() != null) {
				out.append(alert.errorClass() == null ? "" : " em ").append(escape(alert.origin()));
			}
		}
		if (alert.message() != null && !alert.message().isBlank()) {
			out.append('\n').append(escape(alert.message()));
		}
		if (alert.stack() != null && !alert.stack().isBlank() && alert.severity().atLeast(Severity.ERROR)) {
			out.append("\n<pre>").append(escape(alert.stack())).append("</pre>");
		}
		return out.toString();
	}

	private String footer(Bulletin bulletin) {
		List<String> parts = new ArrayList<>();
		if (bulletin.requests() > 0) {
			parts.add("Requisições " + bulletin.requests() + " (4xx " + bulletin.requests4xx() + " · 5xx "
				+ bulletin.requests5xx() + ")");
		}
		if (bulletin.omittedEntries() > 0) {
			parts.add("+" + bulletin.omittedEntries() + " eventos distintos fora deste boletim");
		}
		if (bulletin.mergedServerErrors() > 0) {
			parts.add(bulletin.mergedServerErrors() + " 5xx já contados nas exceções");
		}
		if (bulletin.channelIssues() != null && !bulletin.channelIssues().isBlank()) {
			parts.add("canal: " + bulletin.channelIssues());
		}
		return parts.isEmpty() ? "" : "<i>" + escape(String.join(" · ", parts)) + "</i>";
	}

	private static void line(StringBuilder out, List<String> items) {
		if (!items.isEmpty()) {
			out.append('\n').append(String.join(" · ", items));
		}
	}

	private static String listed(Set<String> values, int distinct) {
		StringBuilder out = new StringBuilder();
		int shown = 0;
		for (String value : values) {
			if (shown == MAX_LISTED) {
				break;
			}
			out.append(shown == 0 ? "" : ", ").append("<code>").append(escape(value)).append("</code>");
			shown++;
		}
		if (distinct > shown) {
			out.append(" +").append(distinct - shown);
		}
		return out.toString();
	}

	/** Last resort for a single oversized entry: cut on a line and close an open {@code <pre>}. */
	private static String clip(String text) {
		if (text.length() <= MAX_MESSAGE) {
			return text;
		}
		String cut = text.substring(0, MAX_MESSAGE);
		int line = cut.lastIndexOf('\n');
		cut = line > MAX_MESSAGE / 2 ? cut.substring(0, line) : cut;
		int amp = cut.lastIndexOf('&');
		if (amp > cut.lastIndexOf(';')) {
			cut = cut.substring(0, amp);
		}
		int tag = cut.lastIndexOf('<');
		if (tag > cut.lastIndexOf('>')) {
			cut = cut.substring(0, tag);
		}
		if (cut.lastIndexOf("<pre>") > cut.lastIndexOf("</pre>")) {
			cut += "</pre>";
		}
		if (cut.lastIndexOf("<code>") > cut.lastIndexOf("</code>")) {
			cut += "</code>";
		}
		return cut + "\n…";
	}

	static String escape(String value) {
		return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Plain-text fallback when Telegram rejects the markup. */
	static String stripHtml(String html) {
		return html.replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
	}

	/** One distinct entry of the window: the first alert of its kind and how often it repeated. */
	record Entry(Alert alert, long count, Instant lastAt, Set<String> ips, int distinctIps, Set<String> users,
			int distinctUsers) {
	}

	/** Everything that goes out for one flush window. */
	record Bulletin(Instant from, Instant to, List<Entry> entries, long omittedEntries, long requests,
			long requests4xx, long requests5xx, long mergedServerErrors, String channelIssues) {
	}
}
