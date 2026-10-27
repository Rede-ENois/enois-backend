package com.socialcoisa.rede.contributions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/** GitHub é vínculo, não login; o fluxo exige sessão e configuração explícita. */
@SpringBootTest
@AutoConfigureMockMvc
class GitHubOAuthLinkTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void githubLoginIsRejectedAndLinkRequiresSession() throws Exception {
		mockMvc.perform(get("/auth/oauth/github/start"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("GITHUB_LINK_ONLY"));

		mockMvc.perform(post("/auth/oauth/github/link"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void linkWithoutOAuthConfigFailsClosed() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("gh_oauth_" + System.nanoTime());
		mockMvc.perform(post("/auth/oauth/github/link")
				.header("X-Session-Token", user.token()))
			.andExpect(status().isNotImplemented())
			.andExpect(jsonPath("$.code").value("OAUTH_NOT_CONFIGURED"));
	}

	@Test
	void statusStartsUnlinked() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("gh_status_" + System.nanoTime());
		mockMvc.perform(get("/me/github").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.linked").value(false));
	}
}
