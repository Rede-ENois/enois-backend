package com.socialcoisa.rede.notifications.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationContractControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void listsNotificationsAndMarksAllRead() throws Exception {
		mockMvc.perform(get("/api/notifications")
				.header("X-User-Id", "notif_user"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());

		mockMvc.perform(post("/api/notifications/read-all")
				.header("X-User-Id", "notif_user")
				.header("Idempotency-Key", "notif-read-all-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ALL_READ"));
	}
}
