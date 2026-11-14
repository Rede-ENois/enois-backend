package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.moderation.application.SuspensionExpiryWorker;
import com.socialcoisa.rede.moderation.application.port.out.AppealRepository;
import com.socialcoisa.rede.moderation.application.port.out.SanctionRepository;
import com.socialcoisa.rede.moderation.domain.Appeal;
import com.socialcoisa.rede.moderation.domain.UserSanction;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * A suspensao com prazo limita o login a decisao e recurso; sem recurso no prazo vira BAN com purga
 * sozinha, e com recurso pendente a conversao espera a decisao humana.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SuspensionFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private SuspensionExpiryWorker expiry;

	@Autowired
	private SanctionRepository sanctions;

	@Autowired
	private AppealRepository appeals;

	@DynamicPropertySource
	static void adminAllowlist(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "susp_admin@test.com");
	}

	@Test
	void suspendedAccountReceivesOnlySanctionsAccess() throws Exception {
		var target = new TestAuthHelper(mockMvc, objectMapper).authenticate("susp_target");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("susp_admin");

		mockMvc.perform(post("/admin/users/susp-target/sanction")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "SUSPENDED", "reason", "Conduta repetida contra as diretrizes da rede."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("SUSPENDED"));

		// Sessao antiga morreu...
		mockMvc.perform(get("/auth/me").header("X-Session-Token", target.token()))
			.andExpect(status().isUnauthorized());

		// ...e a nova autenticacao nao recupera acesso normal.
		mockMvc.perform(post("/auth/login")
				.header("Idempotency-Key", "susp-login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"email", "susp_target@test.com", "password", "Test1234!"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessScope").value("SANCTIONS_ONLY"));
	}

	@Test
	void expiryWithoutAppealConvertsToBan() throws Exception {
		var target = new TestAuthHelper(mockMvc, objectMapper).authenticate("susp_target4");

		String id = "sanc_susp_exp_" + target.userId().replaceAll("[^a-zA-Z0-9]", "");
		sanctions.save(UserSanction.suspended(id, target.userId(), "admin:susp_admin",
			"Suspensao antiga sem recurso para o teste de expiracao.",
			Instant.now().minus(Duration.ofDays(8))));

		expiry.expireSuspensions();

		var active = sanctions.findActiveByUser(target.userId()).orElseThrow();
		assertThat(active.type().name()).isEqualTo("BAN");

		mockMvc.perform(post("/auth/login")
				.header("Idempotency-Key", "susp-exp-login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"email", "susp_target4@test.com", "password", "Test1234!"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_BANNED"));
	}

	@Test
	void pendingAppealBlocksExpiry() throws Exception {
		var target = new TestAuthHelper(mockMvc, objectMapper).authenticate("susp_target5");
		String id = "sanc_susp_pend_" + target.userId().replaceAll("[^a-zA-Z0-9]", "");
		var saved = sanctions.save(UserSanction.suspended(id, target.userId(), "admin:susp_admin",
			"Suspensao com recurso pendente no teste de expiracao.",
			Instant.now().minus(Duration.ofDays(8))));
		appeals.save(Appeal.create("appl_susp_pend_" + target.userId().replaceAll("[^a-zA-Z0-9]", ""),
			saved.id(), target.userId(), "Recurso dentro do prazo de graça.", Instant.now()));

		expiry.expireSuspensions();

		var active = sanctions.findActiveByUser(target.userId()).orElseThrow();
		assertThat(active.type().name()).isEqualTo("SUSPENDED");
	}
}
