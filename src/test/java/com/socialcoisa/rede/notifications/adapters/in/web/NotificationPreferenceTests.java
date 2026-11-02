package com.socialcoisa.rede.notifications.adapters.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.push.application.port.out.PushSender;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Notification preferences: defaults, partial update, and the push filter. The in-app
 * notification is always created; only the push obeys the category toggle, and security is
 * pushed even with every toggle off.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationPreferenceTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private NotificationService notifications;

	@Autowired
	private TransactionTemplate transactions;

	@MockitoBean
	private PushSender pushSender;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void defaultsAreAllOnAndPartialUpdateKeepsTheRest() throws Exception {
		var user = auth.authenticate("npref_crud");

		mockMvc.perform(get("/me/notification-preferences"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/me/notification-preferences").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.weeklyDigest").value(true))
			.andExpect(jsonPath("$.pushSocial").value(true))
			.andExpect(jsonPath("$.pushMessages").value(true))
			.andExpect(jsonPath("$.pushGroups").value(true))
			.andExpect(jsonPath("$.pushJobs").value(true))
			.andExpect(jsonPath("$.pushSystem").value(true));

		mockMvc.perform(put("/me/notification-preferences").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"pushSocial\":false,\"weeklyDigest\":false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pushSocial").value(false))
			.andExpect(jsonPath("$.weeklyDigest").value(false))
			.andExpect(jsonPath("$.pushMessages").value(true))
			.andExpect(jsonPath("$.updatedAt").isNotEmpty());

		// A second partial update touches only its own field.
		mockMvc.perform(put("/me/notification-preferences").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"pushJobs\":false}"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/me/notification-preferences").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pushSocial").value(false))
			.andExpect(jsonPath("$.weeklyDigest").value(false))
			.andExpect(jsonPath("$.pushJobs").value(false))
			.andExpect(jsonPath("$.pushGroups").value(true));
	}

	@Test
	void pushRespectsTheCategoryAndSecurityIsAlwaysSent() throws Exception {
		var user = auth.authenticate("npref_push");
		String userId = user.userId();

		notify(userId, "NEW_REACTION");
		verify(pushSender).sendToUser(eq(userId), any());

		mockMvc.perform(put("/me/notification-preferences").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"pushSocial\":false,\"pushMessages\":false,\"pushGroups\":false,\"pushJobs\":false,\"pushSystem\":false}"))
			.andExpect(status().isOk());

		clearInvocations(pushSender);
		notify(userId, "NEW_REACTION");
		notify(userId, "NEW_MESSAGE");
		notify(userId, "JOB_APPLICATION_STATUS");
		verify(pushSender, never()).sendToUser(eq(userId), any());

		notify(userId, "SEGURANCA");
		verify(pushSender).sendToUser(eq(userId), any());

		// The inbox still has everything: only the push was filtered.
		mockMvc.perform(get("/notifications").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(5));
	}

	/** In its own transaction: the push listener runs after commit. */
	private void notify(String userId, String kind) {
		transactions.executeWithoutResult(status -> notifications.create(userId, kind, "sistema", "Titulo", "Corpo",
			"/feed", "Ver"));
	}
}
