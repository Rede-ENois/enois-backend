package com.socialcoisa.rede.contributions;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * An issue only counts for a seal with a label a maintainer applies. A label that an issue template
 * applies by itself (the bug template applies {@code bug}) would let anyone qualify just by opening an
 * issue, so the labels the deploy configures must never appear in a template.
 */
class QualifiedIssueLabelConfigTests {

	private static final Path TEMPLATES = Path.of(".github/ISSUE_TEMPLATE");
	private static final Path WORKFLOW = Path.of(".github/workflows/build-and-push.yml");
	private static final Pattern TEMPLATE_LABELS = Pattern.compile("(?m)^labels:\\s*\\[(.*)]\\s*$");
	private static final Pattern DEPLOYED_LABELS = Pattern.compile(
		"(?m)^\\s*GITHUB_QUALIFIED_ISSUE_LABELS:\\s*\"?([^\"\\n$]*)\"?\\s*$");

	@Test
	void deployedQualifyingLabelsAreNeverAppliedByAnIssueTemplate() throws IOException {
		Set<String> deployed = deployedLabels();
		assertThat(deployed).as("both deploys configure a qualifying label").isNotEmpty();

		assertThat(templateLabels()).as("labels applied automatically by the issue templates")
			.isNotEmpty()
			.doesNotContainAnyElementsOf(deployed);
	}

	private static Set<String> deployedLabels() throws IOException {
		Set<String> labels = new LinkedHashSet<>();
		Matcher matcher = DEPLOYED_LABELS.matcher(Files.readString(WORKFLOW));
		while (matcher.find()) {
			labels.addAll(split(matcher.group(1)));
		}
		return labels;
	}

	private static Set<String> templateLabels() throws IOException {
		Set<String> labels = new LinkedHashSet<>();
		try (Stream<Path> files = Files.list(TEMPLATES)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".yml")).toList()) {
				Matcher matcher = TEMPLATE_LABELS.matcher(Files.readString(file));
				while (matcher.find()) {
					labels.addAll(split(matcher.group(1).replace("\"", "").replace("'", "")));
				}
			}
		}
		return labels;
	}

	private static List<String> split(String csv) {
		return Stream.of(csv.split(","))
			.map(label -> label.trim().toLowerCase(Locale.ROOT))
			.filter(label -> !label.isEmpty())
			.toList();
	}
}
