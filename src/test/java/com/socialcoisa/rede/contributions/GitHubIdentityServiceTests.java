package com.socialcoisa.rede.contributions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.contributions.application.GitHubIdentityService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/** O ID imutável do GitHub não pode ser compartilhado nem trocado sem desvincular. */
@SpringBootTest
@AutoConfigureMockMvc
class GitHubIdentityServiceTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private GitHubIdentityService identities;

	@Test
	void identityIsIdempotentAndExclusive() throws Exception {
		String suffix = String.valueOf(System.nanoTime());
		var first = new TestAuthHelper(mockMvc, objectMapper).authenticate("ghid_first_" + suffix);
		var second = new TestAuthHelper(mockMvc, objectMapper).authenticate("ghid_second_" + suffix);

		identities.remember(first.userId(), "ghid-" + suffix, "first-login");
		identities.remember(first.userId(), "ghid-" + suffix, "first-login-renamed");
		assertThat(identities.status(first.userId()).login()).isEqualTo("first-login-renamed");

		assertThatThrownBy(() -> identities.remember(second.userId(), "ghid-" + suffix, "other"))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("outro perfil");

		assertThatThrownBy(() -> identities.remember(first.userId(), "ghid-other-" + suffix, "other"))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("outro GitHub");

		identities.unlink(first.userId());
		assertThat(identities.status(first.userId()).linked()).isFalse();
	}
}
