package com.socialcoisa.rede.shared.web;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.moderation.application.AdminAuthorityAudit;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E-09: recusa de autorização em rota administrativa vai para a trilha comum de auditoria, com o
 * motivo certo e sem dado além de método, rota, status, motivo e IP.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminDenialAuditTests {

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("rede.security.denied-admin-alerts.threshold", () -> "2");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private String actor;

	@AfterEach
	void cleanup() {
		if (actor != null) {
			jdbc.update("delete from audit_logs where action = ? and actor_id = ?", AdminAuthorityAudit.ACTION, actor);
		}
	}

	@Test
	void plainMemberIsRecordedWithTheAdminOnlyReason() throws Exception {
		TestAuthHelper.AuthSession session = new TestAuthHelper(mockMvc, objectMapper).authenticate("denaudit_member");
		actor = session.userId();

		mockMvc.perform(get("/admin/reports").header("X-Session-Token", session.token()))
			.andExpect(status().isForbidden());

		Map<String, Object> row = row();
		assertThat(row.get("target_id")).isEqualTo("/admin/reports");
		String metadata = (String) row.get("metadata");
		assertThat(metadata).contains("\"reason\":\"ADMIN_ONLY\"")
			.contains("\"status\":403")
			.contains("\"method\":\"GET\"");
		assertThat(metadata).doesNotContain(session.token());
	}

	@Test
	void moderationRouteRecordsTheModeratorOnlyReason() throws Exception {
		TestAuthHelper.AuthSession session = new TestAuthHelper(mockMvc, objectMapper).authenticate("denaudit_mod");
		actor = session.userId();

		mockMvc.perform(get("/moderation/reports").header("X-Session-Token", session.token()))
			.andExpect(status().isForbidden());

		assertThat((String) row().get("metadata")).contains("\"reason\":\"MODERATOR_ONLY\"");
	}

	@Test
	void missingSessionRecordsTheAnonymousRefusal() throws Exception {
		mockMvc.perform(get("/moderation/reports")).andExpect(status().isUnauthorized());

		Map<String, Object> row = jdbc.queryForMap("select actor_id, target_id, metadata from audit_logs "
			+ "where action = ? and target_id = '/moderation/reports' and actor_id = 'anonymous' "
			+ "order by created_at desc limit 1", AdminAuthorityAudit.ACTION);
		assertThat((String) row.get("metadata")).contains("\"reason\":\"SESSION_MISSING\"")
			.contains("\"status\":401");
	}

	@Test
	void suspeitoApareceNoPainelDeMonitoramentoSoDepoisDoLimiar() throws Exception {
		TestAuthHelper.AuthSession staff = new TestAuthHelper(mockMvc, objectMapper).authenticate("denaudit_staff");
		TestDatabase.setAdmin(staff.userId());
		String groupAdmin = staff.token();
		TestAuthHelper.AuthSession member = new TestAuthHelper(mockMvc, objectMapper).authenticate("denaudit_panel");
		actor = member.userId();

		String before = mockMvc.perform(get("/admin/monitoring/denied-admin").header("X-Session-Token", groupAdmin))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		mockMvc.perform(get("/admin/reports").header("X-Session-Token", member.token()))
			.andExpect(status().isForbidden());

		String afterOne = mockMvc.perform(get("/admin/monitoring/denied-admin").header("X-Session-Token", groupAdmin))
			.andReturn().getResponse().getContentAsString();
		assertThat(afterOne).doesNotContain(member.userId());

		mockMvc.perform(get("/admin/reports").header("X-Session-Token", member.token()))
			.andExpect(status().isForbidden());

		String afterLimit = mockMvc.perform(get("/admin/monitoring/denied-admin").header("X-Session-Token", groupAdmin))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(afterLimit).contains(member.userId()).contains("ADMIN_ONLY");
		assertThat(afterLimit.length()).isGreaterThan(before.length());
	}

	@Test
	void painelDeRecusasExigeModeracao() throws Exception {
		TestAuthHelper.AuthSession member = new TestAuthHelper(mockMvc, objectMapper).authenticate("denaudit_panel2");
		actor = member.userId();

		mockMvc.perform(get("/admin/monitoring/denied-admin").header("X-Session-Token", member.token()))
			.andExpect(status().isForbidden());
		assertThat(Optional.of((String) row().get("metadata")))
			.hasValueSatisfying(metadata -> assertThat(metadata).contains("/admin/monitoring/denied-admin"));
	}

	private Map<String, Object> row() {
		return jdbc.queryForMap("select actor_id, target_id, metadata from audit_logs where action = ? "
			+ "and actor_id = ? order by created_at desc limit 1", AdminAuthorityAudit.ACTION, actor);
	}
}