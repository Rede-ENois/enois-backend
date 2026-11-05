package com.socialcoisa.rede.contributions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.contributions.application.GitHubContributionSyncService;
import com.socialcoisa.rede.contributions.application.GitHubContributionSyncService.SyncReport;
import com.socialcoisa.rede.contributions.application.GitHubIdentityService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/** Botão "Verificar contribuições": só o dono do vínculo, e o resultado volta em contagens. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(GitHubSyncEndpointTests.FakeSyncConfig.class)
class GitHubSyncEndpointTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private GitHubIdentityService identities;

	@TestConfiguration
	static class FakeSyncConfig {
		static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

		@Bean
		@Primary
		GitHubContributionSyncService fakeSync() {
			return new GitHubContributionSyncService(null, null, null, null, null, null, null,
				Clock.systemUTC(), 1, 1, "", "") {
				@Override
				public SyncReport syncForGithubUser(String githubUserId) {
					return new SyncReport(false, false, List.of("Rede-ENois/x"), 10, 2, 1, 0, 0, 0, 3, 1, 0,
						2, false, List.of(), NOW, NOW);
				}
			};
		}
	}

	@Test
	void semVinculoNaoHaOQueVerificar() throws Exception {
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("gh_sync_endpoint_sem");

		mockMvc.perform(post("/me/github/sync").header("X-Session-Token", session.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("GITHUB_NOT_LINKED"));
	}

	@Test
	void vinculoVerificaSoAsContribuicoesDele() throws Exception {
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("gh_sync_endpoint_ok");
		String githubId = "gh-endpoint-" + System.nanoTime();
		identities.remember(session.userId(), githubId, "endpoint-dev");

		mockMvc.perform(post("/me/github/sync").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.contributions").value(3))
			.andExpect(jsonPath("$.issues").value(1))
			.andExpect(jsonPath("$.seals").value(2))
			.andExpect(jsonPath("$.rateLimited").value(false));
	}

	@Test
	void semSessaoNaoVerifica() throws Exception {
		mockMvc.perform(post("/me/github/sync"))
			.andExpect(status().isUnauthorized());
	}
}
