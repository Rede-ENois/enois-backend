package com.socialcoisa.rede.contributions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.contributions.application.GitHubContributionSyncService;
import com.socialcoisa.rede.contributions.application.GitHubIdentityService;
import com.socialcoisa.rede.contributions.application.port.out.GitHubContributionRepository;
import com.socialcoisa.rede.contributions.application.port.out.GitHubGateway;
import com.socialcoisa.rede.contributions.application.port.out.GitHubIssue;
import com.socialcoisa.rede.contributions.application.port.out.GitHubPullRequest;
import com.socialcoisa.rede.contributions.application.port.out.GitHubRepositoryRegistry;
import com.socialcoisa.rede.contributions.domain.ContributionKind;
import com.socialcoisa.rede.contributions.domain.GitHubRepository;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * A sincronização mapeia autor GitHub → conta Énóis para cada PR/issue importado. Antes era uma
 * consulta a {@code github_identities} POR OBJETO da página: uma sincronização de 3 páginas com
 * 60 PRs gastava 60 idas ao banco só de mapeamento, toda sincronização (o worker roda a cada 6h).
 *
 * <p>A prova é a CONTAGEM de consultas a {@code github_identities} durante a rodada: ela não pode
 * crescer com o número de PRs — no máximo uma por página de autores. O mapeamento em si (autor
 * reconhecido ganha a conta certa) é o teste de comportamento que já existe em
 * {@code GitHubContributionSyncTests}; este afirma o custo.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(GitHubContributionSyncQueryTests.FakeGatewayConfig.class)
class GitHubContributionSyncQueryTests {

	@TestConfiguration
	static class InspectorConfig {

		@Bean
		SyncIdentityCountingInspector syncIdentityCountingInspector() {
			return new SyncIdentityCountingInspector();
		}

		@Bean
		org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer syncIdentityInspector(
				SyncIdentityCountingInspector inspector) {
			return properties -> properties.put(
				org.hibernate.cfg.AvailableSettings.STATEMENT_INSPECTOR, inspector);
		}
	}

	/** Captura o SQL da thread do teste (a sincronização roda na própria thread). */
	static class SyncIdentityCountingInspector implements StatementInspector {

		private final AtomicReference<List<String>> captured =
			new AtomicReference<>(Collections.synchronizedList(new ArrayList<>()));

		@Override
		public String inspect(String sql) {
			captured.get().add(sql);
			return sql;
		}

		void reset() {
			captured.set(Collections.synchronizedList(new ArrayList<>()));
		}

		List<String> sql() {
			return List.copyOf(captured.get());
		}
	}

	private static final AtomicReference<List<GitHubPullRequest>> PULLS = new AtomicReference<>(List.of());
	private static final AtomicReference<List<GitHubIssue>> ISSUES = new AtomicReference<>(List.of());
	private static final AtomicReference<List<String>> ORG_REPOS = new AtomicReference<>(List.of());

	@TestConfiguration
	static class FakeGatewayConfig {
		@Bean
		@Primary
		GitHubGateway fakeQueryGateway() {
			return new FakeGateway();
		}
	}

	static class FakeGateway implements GitHubGateway {
		@Override
		public List<String> listOrganizationRepositories(String organization, int maxPages) {
			return ORG_REPOS.get();
		}

		@Override
		public List<GitHubPullRequest> listMergedPullRequests(String repository, Instant since, int maxPages) {
			return PULLS.get();
		}

		@Override
		public List<GitHubIssue> listIssues(String repository, Instant since, int maxPages) {
			return ISSUES.get();
		}

		@Override
		public GitHubPullRequest withDetails(String repository, GitHubPullRequest pullRequest) {
			return pullRequest;
		}

		@Override
		public List<GitHubPullRequest> listOpenPullRequests(String repository, int maxPages) {
			return List.of();
		}

		@Override
		public boolean submitReview(String repository, long number, String decision, String body,
				String tokenOverride) {
			return true;
		}
	}

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private GitHubContributionSyncService sync;
	@Autowired private GitHubRepositoryRegistry registry;
	@Autowired private GitHubIdentityService identities;
	@Autowired private GitHubContributionRepository contributions;
	@Autowired private SyncIdentityCountingInspector inspector;

	@DynamicPropertySource
	static void config(DynamicPropertyRegistry registry) {
		registry.add("rede.github.sync-enabled", () -> "false");
	}

	private final String sufixo = String.valueOf(System.nanoTime());

	@AfterEach
	void limparFixtures() {
		PULLS.set(List.of());
		ISSUES.set(List.of());
		ORG_REPOS.set(List.of());
	}

	@Test
	void sincronizacaoMapeiaAutoresSemConsultaPorObjeto() throws Exception {
		String repo = "Rede-ENois/sync-q-" + sufixo;
		registry.save(new GitHubRepository(repo, true, Instant.now(), null));

		// Oito PRs de oito autores distintos: o pior caso da consulta por objeto.
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("gh_syncq_" + sufixo);
		identities.remember(session.userId(), "gh-q-0", "dev-q-0");
		Instant now = Instant.now();
		List<GitHubPullRequest> pulls = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			pulls.add(new GitHubPullRequest(1000 + i, "node-q-" + i, "PR " + i,
				"https://github.com/" + repo + "/pull/" + (1000 + i), "", "gh-q-" + i, "dev-q-" + i,
				"closed", now.minusSeconds(3600), now, now, 10, 1, 1, List.of(), 0, 0, 0));
		}
		PULLS.set(pulls);
		ISSUES.set(List.of());

		inspector.reset();
		var report = sync.sync(false, true, List.of(repo));

		assertThat(report.created()).isEqualTo(8);
		// O autor reconhecido (gh-q-0) ganhou a conta certa: o lote preserva o mapeamento.
		assertThat(contributions.findByRepositoryAndKindAndNumber(repo, ContributionKind.PULL_REQUEST, 1000)
			.orElseThrow().userId()).isEqualTo(session.userId());

		var identityQueries = inspector.sql().stream()
			.filter(sql -> sql.toLowerCase().contains("from github_identities"))
			.filter(sql -> sql.toLowerCase().contains("select"))
			.toList();
		// UMA consulta em lote para a página inteira, não uma por PR. (O `select ... in (...)` da
		// consulta dirigida; o `count` da UI de administração não entra porque não roda aqui.)
		assertThat(identityQueries).hasSize(1);
		assertThat(identityQueries.get(0).toLowerCase()).contains("in (");
	}
}
