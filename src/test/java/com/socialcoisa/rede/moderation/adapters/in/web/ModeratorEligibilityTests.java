package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/** O limiar real do produto: sem pelo menos 200 de credibilidade, a candidatura e recusada. */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("critico")
class ModeratorEligibilityTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void newAccountWithLowReputationCannotApply() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("melow_mod");

		mockMvc.perform(get("/moderation/me").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.minReputation").value(200))
			.andExpect(jsonPath("$.eligible").value(false))
			.andExpect(jsonPath("$.moderator").value(false));

		mockMvc.perform(post("/moderation/applications")
				.header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"motivation", "Quero muito ajudar a comunidade a crescer com seguranca."))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("LOW_REPUTATION"));
	}

	@Test
	void directSanctionRequiresAdmin() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("melow_notadmin");
		var target = new TestAuthHelper(mockMvc, objectMapper).authenticate("melow_target");

		mockMvc.perform(post("/admin/users/melow_target/sanction")
				.header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "RESTRICTION", "reason", "tentativa sem permissao"))))
			.andExpect(status().isForbidden());
	}
}
