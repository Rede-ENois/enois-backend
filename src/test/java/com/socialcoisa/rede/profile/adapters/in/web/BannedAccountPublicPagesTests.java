package com.socialcoisa.rede.profile.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;

/**
 * A banned account is gone from every public page keyed by its @ or id, not only from the profile
 * read: the side pages (posts, portfolio, seals, games, projects, work page, recommendations) follow.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BannedAccountPublicPagesTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void sidePagesOfABannedAccountAreNotFound() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		AuthSession banned = auth.authenticate("ban_pages_" + System.nanoTime());
		String username = objectMapper.readTree(mockMvc.perform(get("/me").header("X-Session-Token", banned.token()))
			.andReturn().getResponse().getContentAsString()).get("username").asString();
		String workUsername = auth.activateProfessional(banned);
		List<String> routes = List.of(
			"/users/" + username + "/posts",
			"/users/" + username + "/projects",
			"/users/" + username + "/seals",
			"/games/" + username,
			"/community-projects/" + username,
			"/corporate/" + workUsername,
			"/corporate/" + workUsername + "/posts",
			"/recommendations/received/" + banned.userId(),
			"/recommendations/given/" + banned.userId());
		for (String route : routes) {
			mockMvc.perform(get(route)).andExpect(status().isOk());
		}

		AuthSession admin = auth.authenticate("group-admin");
		mockMvc.perform(post("/admin/users/{username}/sanction", username)
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "BAN", "reason", "Conta de teste banida na triagem."))))
			.andExpect(status().isOk());

		for (String route : routes) {
			mockMvc.perform(get(route)).andExpect(status().isNotFound());
		}
	}
}
