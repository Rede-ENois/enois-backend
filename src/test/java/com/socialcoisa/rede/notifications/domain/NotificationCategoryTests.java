package com.socialcoisa.rede.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every notification kind created anywhere in the code maps to a push category, so a new kind
 * cannot silently skip the person's push preferences.
 */
class NotificationCategoryTests {

	private static final Path SOURCES = Path.of("src/main/java");

	/** {@code notifications.create(userId, "KIND", ...)} on the NotificationService. */
	private static final Pattern CREATE_CALL = Pattern.compile(
		"(?:notifications|notificationService)\\s*\\.create\\(\\s*[^,;]+?,\\s*([^,;]+?)\\s*,");

	private static final Pattern KIND_LITERAL = Pattern.compile("\"([A-Z][A-Z_]+)\"");

	@Test
	void everyKindInTheCodeHasACategory() throws IOException {
		TreeSet<String> kinds = new TreeSet<>();
		List<String> variableKindSites = new ArrayList<>();
		try (Stream<Path> files = Files.walk(SOURCES)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String source = Files.readString(file);
				Matcher call = CREATE_CALL.matcher(source);
				while (call.find()) {
					String argument = call.group(1).trim();
					Matcher literal = KIND_LITERAL.matcher(argument);
					if (literal.matches()) {
						kinds.add(literal.group(1));
					}
					else {
						variableKindSites.add(file.getFileName() + ":" + argument);
					}
				}
				if (file.getFileName().toString().equals("MessagingService.java")) {
					// Message notifications pick NEW_MESSAGE or NEW_WORK_MESSAGE by conversation context.
					Matcher literal = Pattern.compile("\"(NEW_(?:WORK_)?MESSAGE)\"").matcher(source);
					while (literal.find()) {
						kinds.add(literal.group(1));
					}
				}
				if (file.getFileName().toString().equals("GroupNotifier.java")) {
					// The group notifier passes the kind through a variable: its literals are the kinds.
					Matcher literal = Pattern.compile("\"(GROUP_[A-Z_]+)\"").matcher(source);
					while (literal.find()) {
						kinds.add(literal.group(1));
					}
				}
			}
		}

		assertThat(kinds).contains("SEGURANCA", "NEW_MESSAGE", "NEW_WORK_MESSAGE", "JOB_APPLICATION_STATUS", "GROUP_JOIN_REQUEST");
		assertThat(kinds).allSatisfy(kind -> assertThat(NotificationCategory.isMapped(kind))
			.as("kind %s needs a push category in NotificationCategory", kind).isTrue());
		// A new call site with a computed kind must be read here too, or it escapes this check.
		assertThat(variableKindSites).containsOnly("GroupNotifier.java:kind", "MessagingService.java:messageNotificationKind(conversation)");
	}

	@Test
	void securityModerationAndSanctionsAreAlwaysPushed() {
		NotificationPreferences everythingOff = new NotificationPreferences(false, false, false, false, false, false,
			null);
		assertThat(everythingOff.allowsPush("SEGURANCA")).isTrue();
		assertThat(everythingOff.allowsPush("MODERACAO")).isTrue();
		assertThat(everythingOff.allowsPush("SANCAO")).isTrue();
		assertThat(everythingOff.allowsPush("RECURSO")).isTrue();
		assertThat(everythingOff.allowsPush("NEW_REACTION")).isFalse();
		assertThat(everythingOff.allowsPush("UNKNOWN_KIND")).isFalse();
	}
}
