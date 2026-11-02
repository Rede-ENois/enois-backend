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

import static org.assertj.core.api.Assertions.assertThat;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationContractControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void listsNotificationsAndMarksAllRead() throws Exception {
		var session = auth.authenticate("notif_user");

		// Sem conteudo, so o contrato do array vazio.
		mockMvc.perform(get("/notifications")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());

		mockMvc.perform(post("/notifications/read-all")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "notif-read-all-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ALL_READ"));
	}

	@Test
	void marksASingleNotificationReadWithoutError() throws Exception {
		var session = auth.authenticate("notif_read_one");
		jdbc.update("""
				insert into notifications (id, user_id, kind, actor_id, title, body, is_read, created_at)
				values (?, ?, 'SYSTEM', 'sistema', 'Aviso', 'corpo', false, current_timestamp)
				""", "notif_read_id_1", session.userId());

		mockMvc.perform(post("/notifications/notif_read_id_1/read")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "notif-read-one"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/notifications")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == 'notif_read_id_1')].read").value(true));
	}

	@Test
	void unreadSummaryCountsUnreadPerAreaWithoutBlockedActorsAndHonorsTheContext() throws Exception {
		var session = auth.authenticate("notif_summary_owner");
		var blocked = auth.authenticate("notif_summary_blocked");
		String me = session.userId();
		unread(me, "summary_msg_1", "NEW_MESSAGE", "notif_summary_friend", false);
		unread(me, "summary_msg_2", "NEW_MESSAGE", "notif_summary_friend", false);
		unread(me, "summary_work_msg", "NEW_WORK_MESSAGE", "notif_summary_friend", false);
		unread(me, "summary_group", "GROUP_JOIN_REQUEST", "notif_summary_friend", false);
		unread(me, "summary_security", "SEGURANCA", "sistema", false);
		unread(me, "summary_read_group", "GROUP_NEW_POST", "notif_summary_friend", true);
		unread(me, "summary_blocked_group", "GROUP_NEW_POST", blocked.userId(), false);
		mockMvc.perform(post("/network/blocks/" + blocked.userId())
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "notif-summary-block"))
			.andExpect(status().isCreated());

		mockMvc.perform(get("/notifications/unread-summary").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(5))
			.andExpect(jsonPath("$.byModule.MESSAGES").value(3))
			.andExpect(jsonPath("$.byModule.GROUPS").value(1))
			.andExpect(jsonPath("$.byModule.OTHER").value(1));

		// The professional interface keeps work kinds plus the account-wide ones (security).
		mockMvc.perform(get("/notifications/unread-summary").param("context", "PROFESSIONAL")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.byModule.MESSAGES").value(1))
			.andExpect(jsonPath("$.byModule.OTHER").value(1))
			.andExpect(jsonPath("$.byModule.GROUPS").doesNotExist());

		mockMvc.perform(get("/notifications/unread-summary").param("context", "SOCIAL")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.byModule.MESSAGES").value(2));

		mockMvc.perform(get("/notifications/unread-summary").param("context", "OTHER")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isBadRequest());
	}

	@Test
	void everyListedItemCarriesTheModuleOfItsKind() throws Exception {
		var session = auth.authenticate("notif_module_owner");
		unread(session.userId(), "module_event", "EVENT_NEXT_OCCURRENCE", "notif_module_friend", false);
		unread(session.userId(), "module_reaction", "NEW_REACTION", "notif_module_friend", false);

		mockMvc.perform(get("/notifications").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == 'module_event')].module").value("EVENTS"))
			.andExpect(jsonPath("$.items[?(@.id == 'module_reaction')].module").value("OTHER"));
	}

	private void unread(String userId, String id, String kind, String actorId, boolean read) {
		jdbc.update("""
				insert into notifications (id, user_id, kind, actor_id, title, body, is_read, created_at)
				values (?, ?, ?, ?, 'Aviso', 'corpo', ?, current_timestamp)
				""", id, userId, kind, actorId, read);
	}
}
