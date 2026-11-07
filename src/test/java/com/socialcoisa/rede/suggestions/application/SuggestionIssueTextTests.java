package com.socialcoisa.rede.suggestions.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The public issue text must stay inert on GitHub: no mention, no issue reference, no issue URL, while
 * remaining readable once the zero-width spaces are removed.
 */
class SuggestionIssueTextTests {

	private static final Pattern MENTION = Pattern.compile("@[A-Za-z0-9-]");
	private static final Pattern REFERENCE = Pattern.compile("(?i)(#|GH-)\\d");
	private static final Pattern GITHUB_URL = Pattern.compile("(?i)github\\.com");

	@Test
	void mentionsReferencesAndIssueUrlsAreNeutralized() {
		String text = "Ideia de @pessoa, ver #12, org/repo#3, user#4, gh-5 e https://github.com/org/repo/issues/6";

		String neutral = SuggestionService.neutralizeMentions(text);

		assertFalse(MENTION.matcher(neutral).find(), neutral);
		assertFalse(REFERENCE.matcher(neutral).find(), neutral);
		assertFalse(GITHUB_URL.matcher(neutral).find(), neutral);
		assertEquals(text, neutral.replace("​", ""));
	}

	@Test
	void plainHashtagsAndEmptyTextAreLeftAlone() {
		assertEquals("#acessibilidade no app", SuggestionService.neutralizeMentions("#acessibilidade no app"));
		assertEquals("", SuggestionService.neutralizeMentions("  "));
		assertTrue(SuggestionService.neutralizeMentions(null).isEmpty());
	}
}
