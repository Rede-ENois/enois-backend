package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/** "Voce esta em dia": the per-person marker returned on the first feed page and moved by POST /feed/seen. */
@SpringBootTest
@AutoConfigureMockMvc
class FeedSeenMarkerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void firstVisitHasNoMarkerAndTheNextFirstPageReturnsIt() throws Exception {
		var leitor = auth.authenticate("seenmk_first");
		mockMvc.perform(get("/feed").param("tab", "FOR_YOU").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray())
			.andExpect(jsonPath("$.lastSeenAt").value(nullValue()));

		Instant seenUntil = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
		markSeen(leitor.token(), seenUntil.toString());

		assertThat(lastSeenAt(leitor.token())).isEqualTo(seenUntil);
	}

	@Test
	void markerNeverMovesBack() throws Exception {
		var leitor = auth.authenticate("seenmk_mono");
		Instant newer = Instant.now().minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.MICROS);
		markSeen(leitor.token(), newer.toString());
		markSeen(leitor.token(), newer.minus(Duration.ofDays(2)).toString());

		assertThat(lastSeenAt(leitor.token())).isEqualTo(newer);
	}

	@Test
	void futureValuesAreClampedToNow() throws Exception {
		var leitor = auth.authenticate("seenmk_future");
		markSeen(leitor.token(), Instant.now().plus(Duration.ofDays(3)).toString());

		Instant stored = lastSeenAt(leitor.token());
		assertThat(stored).isBeforeOrEqualTo(Instant.now()).isAfter(Instant.now().minus(Duration.ofMinutes(1)));
	}

	@Test
	void cursorPagesDoNotCarryTheMarker() throws Exception {
		var leitor = auth.authenticate("seenmk_cursor");
		markSeen(leitor.token(), Instant.now().minus(Duration.ofHours(2)).toString());

		mockMvc.perform(get("/feed").param("tab", "FOR_YOU").param("cursor", Instant.now().toString())
				.header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lastSeenAt").value(nullValue()));
	}

	@Test
	void onlyForYouCarriesTheMarker() throws Exception {
		var leitor = auth.authenticate("seenmk_tabs");
		markSeen(leitor.token(), Instant.now().minus(Duration.ofHours(2)).toString());

		for (String tab : new String[] { "ALL", "UNSEEN", "TRENDING", "INTERACTED", "PROJECTS" }) {
			mockMvc.perform(get("/feed").param("tab", tab).header("X-Session-Token", leitor.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lastSeenAt").value(nullValue()));
		}
		mockMvc.perform(get("/feed").param("tab", "FOR_YOU").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lastSeenAt").isNotEmpty());
	}

	@Test
	void missingSeenUntilIsRejected() throws Exception {
		var leitor = auth.authenticate("seenmk_invalid");
		mockMvc.perform(post("/feed/seen").header("X-Session-Token", leitor.token())
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_SEEN_UNTIL"));
	}

	@Test
	void anonymousCannotMoveTheMarkerNorReadTheFeed() throws Exception {
		mockMvc.perform(post("/feed/seen").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("seenUntil", Instant.now().toString()))))
			.andExpect(status().isUnauthorized());
		// GET /feed already requires a session, so an anonymous visitor never gets a marker.
		mockMvc.perform(get("/feed")).andExpect(status().isUnauthorized());
	}

	private void markSeen(String token, String seenUntil) throws Exception {
		mockMvc.perform(post("/feed/seen").header("X-Session-Token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("seenUntil", seenUntil))))
			.andExpect(status().isNoContent());
	}

	private Instant lastSeenAt(String token) throws Exception {
		String body = mockMvc.perform(get("/feed").param("tab", "FOR_YOU").header("X-Session-Token", token))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return Instant.parse(objectMapper.readTree(body).get("lastSeenAt").asText());
	}
}
