package com.socialcoisa.rede.presence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class PresenceControllerTests {
	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private JdbcTemplate jdbc;

	@Test
	void persistsSelectionAndHidesInvisibleStatusFromOtherUsers() throws Exception {
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("presence_" + System.nanoTime());
		String username = objectMapper.readTree(mockMvc.perform(get("/me")
			.header("X-Session-Token", session.token())).andReturn().getResponse().getContentAsString())
			.get("username").asText();

		mockMvc.perform(get("/me/presence").header("X-Session-Token", session.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE"));
		mockMvc.perform(put("/me/presence").header("X-Session-Token", session.token())
			.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INVISIBLE\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVISIBLE"));
		mockMvc.perform(post("/me/presence/heartbeat").header("X-Session-Token", session.token()))
			.andExpect(status().isNoContent());
		mockMvc.perform(get("/me/presence").header("X-Session-Token", session.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVISIBLE"));
		mockMvc.perform(get("/users/" + username + "/presence"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OFFLINE"));
	}

	@Test
	void rejectsMissingSessionAndInvalidStatus() throws Exception {
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("presence_invalid_" + System.nanoTime());
		mockMvc.perform(get("/me/presence")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/me/presence/heartbeat")).andExpect(status().isUnauthorized());
		mockMvc.perform(put("/me/presence").contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"AWAY\"}")).andExpect(status().isUnauthorized());
		mockMvc.perform(put("/me/presence").header("X-Session-Token", session.token())
			.contentType(MediaType.APPLICATION_JSON).content("{\"status\":null}"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(put("/me/presence").header("X-Session-Token", session.token())
			.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OFFLINE\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void heartbeatCreatesAvailablePresenceAndPreservesSelection() throws Exception {
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("presence_states_" + System.nanoTime());
		String username = objectMapper.readTree(mockMvc.perform(get("/me")
			.header("X-Session-Token", session.token())).andReturn().getResponse().getContentAsString())
			.get("username").asText();
		mockMvc.perform(get("/users/" + username + "/presence"))
			.andExpect(jsonPath("$.status").value("OFFLINE"));
		mockMvc.perform(post("/me/presence/heartbeat").header("X-Session-Token", session.token()))
			.andExpect(status().isNoContent());
		mockMvc.perform(get("/users/" + username + "/presence"))
			.andExpect(jsonPath("$.status").value("AVAILABLE"));
		for (String selection : new String[] {"AWAY", "DO_NOT_DISTURB", "AVAILABLE"}) {
			mockMvc.perform(put("/me/presence").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + selection + "\"}"))
				.andExpect(status().isOk());
			mockMvc.perform(post("/me/presence/heartbeat").header("X-Session-Token", session.token()))
				.andExpect(status().isNoContent());
			mockMvc.perform(get("/users/" + username + "/presence"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value(selection));
		}
		jdbc.update("update user_presence set last_seen_at = ? where user_id = ?",
			Timestamp.from(Instant.now().minus(3, ChronoUnit.MINUTES)), session.userId());
		mockMvc.perform(get("/users/" + username + "/presence"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OFFLINE"));
	}
}
