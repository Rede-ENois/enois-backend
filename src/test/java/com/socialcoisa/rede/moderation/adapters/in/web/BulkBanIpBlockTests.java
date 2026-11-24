package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import org.junit.jupiter.api.Tag;
import tools.jackson.databind.ObjectMapper;

/**
 * O lote bloqueia o IP REPETIDO (2+ contas banidas no mesmo lote) — o sinal de cadastro em série.
 *
 * A suíte inteira fala de 127.0.0.1, então este cenário liga o bloqueio só no próprio contexto e
 * limpa a linha de blocked_ips no fim: sem isso, os logins dos outros testes levariam IP_BLOCKED.
 */
@Tag("critico")
@SpringBootTest
@AutoConfigureMockMvc
class BulkBanIpBlockTests {

	private static final String LOOPBACK = "127.0.0.1";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "bb_admin@test.com");
		registry.add("rede.moderation.bulk-ban.block-ips", () -> "true");
	}

	@Test
	void repeatedIpOfTheBatchIsBlockedAndCleanedUp() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var admin = auth.authenticate("bb_admin");
		auth.authenticate("probe_ip_one");
		auth.authenticate("probe_ip_two");

		jdbc.update("delete from blocked_ips where ip = ?", LOOPBACK);
		try {
			MvcResult banned = mockMvc.perform(post("/moderation/users/bulk-ban")
					.header("X-Session-Token", admin.token())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of(
						"usernames", List.of("probe-ip-one", "probe-ip-two"),
						"type", "BAN",
						"reason", "Cadastros em série na triagem de contas."))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.banned").value(2))
				.andReturn();

			assertThat(objectMapper.readTree(banned.getResponse().getContentAsString()).get("blockedIps").toString())
				.contains(LOOPBACK);

			// O IP fechado barra a próxima conta antes mesmo do login.
			mockMvc.perform(post("/auth/login")
					.header("Idempotency-Key", "ip-block-login-1")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of(
						"email", "probe_ip_one@test.com", "password", "Test1234!"))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("IP_BLOCKED"));
		}
		finally {
			jdbc.update("delete from blocked_ips where ip = ?", LOOPBACK);
		}
	}
}
