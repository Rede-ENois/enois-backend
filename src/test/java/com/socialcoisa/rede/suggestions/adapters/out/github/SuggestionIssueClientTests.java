package com.socialcoisa.rede.suggestions.adapters.out.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.suggestions.application.port.out.SuggestionIssueGateway.Availability;
import com.socialcoisa.rede.suggestions.application.port.out.SuggestionIssueGateway.IssueRef;
import com.socialcoisa.rede.suggestions.domain.SuggestionTarget;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * The suggestions token is the module's own configuration (never the support token), each target only
 * turns on with a valid repository, and a retry after an unknown outcome reuses the issue it finds.
 */
class SuggestionIssueClientTests {

	private static final Pattern PAGE = Pattern.compile("(?:^|&)page=(\\d+)");

	private HttpServer server;
	private final List<String> requests = new CopyOnWriteArrayList<>();
	private volatile String listResponse = "[]";
	/** Per-page list bodies keyed by the {@code page} query value; falls back to {@link #listResponse}. */
	private final Map<String, String> listPages = new ConcurrentHashMap<>();
	private volatile int listStatus = 200;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
					+ exchange.getRequestHeaders().getFirst("Authorization"));
			boolean create = "POST".equals(exchange.getRequestMethod());
			Matcher page = PAGE.matcher(String.valueOf(exchange.getRequestURI().getQuery()));
			String list = page.find() ? listPages.getOrDefault(page.group(1), listResponse) : listResponse;
			byte[] body = (create ? "{\"number\":42,\"html_url\":\"https://github.com/org/backend/issues/42\"}"
					: list).getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(create ? 201 : listStatus, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private SuggestionIssueClient client(String token, String backend, String frontend, String designSystem) {
		return new SuggestionIssueClient(JsonMapper.builder().build(),
			"http://127.0.0.1:" + server.getAddress().getPort(), token, backend, frontend, designSystem);
	}

	@Test
	void turnsOnOnlyTargetsWithARepositoryWhenItsOwnTokenIsSet() {
		var client = client("suggestions-token", "org/backend", "", "org/design-system");

		assertEquals(Availability.READY, client.availability(SuggestionTarget.BACKEND));
		assertEquals(Availability.REPOSITORY_MISSING, client.availability(SuggestionTarget.FRONTEND));
		assertEquals(Availability.READY, client.availability(SuggestionTarget.DESIGN_SYSTEM));
	}

	@Test
	void withoutItsOwnTokenEveryTargetReportsTheMissingToken() {
		for (String token : new String[] { "", "   ", null }) {
			var client = client(token, "org/backend", "org/frontend", "org/design-system");
			for (SuggestionTarget target : SuggestionTarget.values()) {
				assertEquals(Availability.TOKEN_MISSING, client.availability(target));
			}
		}
	}

	@Test
	void malformedRepositoryTurnsTheTargetOff() {
		var client = client("suggestions-token", "https://github.com/org/backend", "org", "org/design system");

		for (SuggestionTarget target : SuggestionTarget.values()) {
			assertEquals(Availability.REPOSITORY_MISSING, client.availability(target));
		}
	}

	@Test
	void firstAttemptCreatesWithTheHiddenKeyAndItsOwnToken() {
		var issue = client("suggestions-token", "org/backend", "", "")
			.publish(SuggestionTarget.BACKEND, "sugg_1", "Título", "Texto", null);

		assertEquals(new IssueRef(42, "https://github.com/org/backend/issues/42"), issue.orElseThrow());
		assertEquals(1, requests.size());
		assertTrue(requests.getFirst().startsWith("POST /repos/org/backend/issues "));
		assertTrue(requests.getFirst().endsWith("Bearer suggestions-token"));
	}

	@Test
	void retryAfterAnUnknownOutcomeReusesTheIssueCarryingTheKey() {
		listResponse = "[{\"number\":7,\"html_url\":\"https://github.com/org/backend/issues/7\",\"body\":\"x\"},"
				+ "{\"number\":9,\"html_url\":\"https://github.com/org/backend/issues/9\",\"body\":\"Texto\\n\\n"
				+ SuggestionIssueClient.marker("sugg_2") + "\"}]";

		var issue = client("suggestions-token", "org/backend", "", "")
			.publish(SuggestionTarget.BACKEND, "sugg_2", "Título", "Texto", Instant.parse("2026-11-06T10:00:00Z"));

		assertEquals(9, issue.orElseThrow().number());
		assertEquals(1, requests.size(), "found by its key: no second issue");
		assertTrue(requests.getFirst().startsWith("GET /repos/org/backend/issues?"));
	}

	@Test
	void retryCreatesWhenNoIssueCarriesTheKey() {
		listResponse = "[{\"number\":7,\"body\":\"" + SuggestionIssueClient.marker("sugg_other") + "\"}]";

		var issue = client("suggestions-token", "org/backend", "", "")
			.publish(SuggestionTarget.BACKEND, "sugg_3", "Título", "Texto", Instant.parse("2026-11-06T10:00:00Z"));

		assertEquals(42, issue.orElseThrow().number());
		assertEquals(2, requests.size());
	}

	@Test
	void retryNeverCreatesBlindlyWhenTheLookupFails() {
		listStatus = 500;

		var issue = client("suggestions-token", "org/backend", "", "")
			.publish(SuggestionTarget.BACKEND, "sugg_4", "Título", "Texto", Instant.parse("2026-11-06T10:00:00Z"));

		assertTrue(issue.isEmpty());
		assertTrue(requests.stream().noneMatch(request -> request.startsWith("POST")));
	}

	@Test
	void retryFindsTheIssueBeyondTheFirstPage() {
		listPages.put("1", fullPageWithout("sugg_5"));
		listPages.put("2", "[{\"number\":301,\"body\":\"Texto\\n\\n" + SuggestionIssueClient.marker("sugg_5") + "\"}]");

		var issue = client("suggestions-token", "org/backend", "", "")
			.publish(SuggestionTarget.BACKEND, "sugg_5", "Título", "Texto", Instant.parse("2026-11-06T10:00:00Z"));

		assertEquals(301, issue.orElseThrow().number());
		assertTrue(requests.stream().noneMatch(request -> request.startsWith("POST")));
	}

	@Test
	void retryNeverCreatesBlindlyWhenThePageBudgetRunsOut() {
		listResponse = fullPageWithout("sugg_6");

		var issue = client("suggestions-token", "org/backend", "", "")
			.publish(SuggestionTarget.BACKEND, "sugg_6", "Título", "Texto", Instant.parse("2026-11-06T10:00:00Z"));

		assertTrue(issue.isEmpty());
		assertEquals(10, requests.size());
		assertTrue(requests.stream().noneMatch(request -> request.startsWith("POST")));
	}

	private static String fullPageWithout(String key) {
		return IntStream.range(0, 100)
			.mapToObj(i -> "{\"number\":" + (1000 + i) + ",\"body\":\"" + SuggestionIssueClient.marker("other_" + i) + "\"}")
			.collect(Collectors.joining(",", "[", "]"));
	}
}
