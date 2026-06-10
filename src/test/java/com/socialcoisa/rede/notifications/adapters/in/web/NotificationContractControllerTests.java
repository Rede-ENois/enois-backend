package com.socialcoisa.rede.notifications.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationContractControllerTests {

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
	void listsNotificationsAndMarksAllRead() throws Exception {
		var session = auth.authenticate("notif_user");

		mockMvc.perform(get("/api/notifications")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());

		mockMvc.perform(post("/api/notifications/read-all")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "notif-read-all-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ALL_READ"));
	}
}
