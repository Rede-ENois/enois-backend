package com.socialcoisa.rede.events.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * Viewer attendance on list/detail: after POST .../attendees, authenticated GET must expose
 * attending=true for that event; anonymous list always returns attending=false.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventViewerAttendanceTests {

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
	void attendThenAuthenticatedListShowsAttendingTrue() throws Exception {
		var host = auth.authenticate("evattend_host");
		var guest = auth.authenticate("evattend_guest");

		String eventId = objectMapper
			.readTree(create(host, "Meetup attendance flag", "2027-08-10", "evattend-create").andReturn()
				.getResponse().getContentAsString())
			.get("id").asText();

		mockMvc.perform(post("/events/" + eventId + "/attendees")
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "evattend-rsvp"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ATTENDING"));

		mockMvc.perform(get("/events").header("X-Session-Token", guest.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + eventId + "')].attending").value(true));

		mockMvc.perform(get("/events/" + eventId).header("X-Session-Token", guest.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attending").value(true));
	}

	@Test
	void anonymousListAlwaysReturnsAttendingFalse() throws Exception {
		var host = auth.authenticate("evattend_anon_host");

		String eventId = objectMapper
			.readTree(create(host, "Public list attending false", "2027-09-01", "evattend-anon-create")
				.andReturn().getResponse().getContentAsString())
			.get("id").asText();

		mockMvc.perform(get("/events"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + eventId + "')].attending").value(false));
	}

	@Test
	void cancelThenDetailShowsAttendingFalse() throws Exception {
		var host = auth.authenticate("evattend_cancel_host");
		var guest = auth.authenticate("evattend_cancel_guest");

		String eventId = objectMapper
			.readTree(create(host, "Cancel attendance flag", "2027-10-12", "evattend-cancel-create")
				.andReturn().getResponse().getContentAsString())
			.get("id").asText();

		mockMvc.perform(post("/events/" + eventId + "/attendees")
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "evattend-cancel-rsvp"))
			.andExpect(status().isOk());

		mockMvc.perform(delete("/events/" + eventId + "/attendees")
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "evattend-cancel"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("NOT_ATTENDING"));

		mockMvc.perform(get("/events/" + eventId).header("X-Session-Token", guest.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attending").value(false));
	}

	private org.springframework.test.web.servlet.ResultActions create(TestAuthHelper.AuthSession creator,
			String title, String date, String key) throws Exception {
		return mockMvc.perform(post("/events")
			.header("X-Session-Token", creator.token())
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("title", title, "date", date, "time", "19:00",
				"format", "ONLINE", "host", "Énóis"))));
	}

}
