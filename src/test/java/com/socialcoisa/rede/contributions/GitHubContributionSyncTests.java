package com.socialcoisa.rede.contributions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
import com.socialcoisa.rede.contributions.application.port.out.UserSealRepository;
import com.socialcoisa.rede.contributions.domain.ContributionKind;
import com.socialcoisa.rede.contributions.domain.ContributionStatus;
import com.socialcoisa.rede.contributions.domain.GitHubRepository;
import com.socialcoisa.rede.contributions.domain.SealStatus;
import com.socialcoisa.rede.contributions.domain.SealType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/** Sync idempotente de PR e issue, mapeamento por ID imutável, qualificação e revert. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(GitHubContributionSyncTests.FakeGatewayConfig.class)
class GitHubContributionSyncTests {

	@Autowired
	private MockMvc mockMvc;

	private static final AtomicReference<List<GitHubPullRequest>> PULLS = new AtomicReference<>(List.of());
	private static final AtomicReference<List<GitHubIssue>> ISSUES = new AtomicReference<>(List.of());
	private static final String HEAD = "gh-103";

	@Autowired
	private GitHubContributionSyncService sync;

	@Autowired
	private GitHubRepositoryRegistry registry;

	@Autowired
	private GitHubIdentityService identities;

	@Autowired
	private GitHubContributionRepository contributions;

	@Autowired
	private UserSealRepository seals;

	@Autowired
	private ObjectMapper objectMapper;

	@DynamicPropertySource
	static void config(DynamicPropertyRegistry registry) {
		registry.add("rede.github.sync-enabled", () -> "false");
		registry.add("rede.github.qualified-issue-labels", () -> "qualificado");
	}

	@TestConfiguration
	static class FakeGatewayConfig {
		@Bean
		@Primary
		GitHubGateway fakeGitHubGateway() {
			return new FakeGateway();
		}
	}

	static class FakeGateway implements GitHubGateway {
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
	}

	@Test
	void syncIsIdempotentMapsIdentityAndFlagsReverts() throws Exception {
		String suffix = String.valueOf(System.nanoTime());
		String repo = "Rede-ENois/sync-test-" + suffix;
		registry.save(new GitHubRepository(repo, true, Instant.now(), null));

		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("gh_sync_" + suffix);
		identities.remember(session.userId(), HEAD, "dev-103");

		Instant now = Instant.now();
		ISSUES.set(List.of());
		PULLS.set(List.of(
			pull(103, "gh-103", "dev-103", "Corrige cálculo de saldo", List.of("src/main/Balance.java",
				"src/test/BalanceTest.java"), 40, 4, 2, 1, now),
			pull(101, "gh-101", "dev-101", "Adiciona teste de integração", List.of("src/main/Foo.java",
				"src/test/FooTest.java"), 50, 5, 2, 1, now),
			revert(102, "gh-102", "dev-102", 101, now)
		));

		var first = sync.sync(false, true, List.of(repo));
		assertThat(first.created()).isEqualTo(3);
		assertThat(first.revertedFlagged()).isEqualTo(1);
		assertThat(first.sealsRecalculated()).isEqualTo(1);

		var head = contributions.findByRepositoryAndKindAndNumber(repo, ContributionKind.PULL_REQUEST, 103)
			.orElseThrow();
		assertThat(head.userId()).isEqualTo(session.userId());
		assertThat(head.detailsFetched()).isTrue();
		assertThat(head.hasTests()).isTrue();
		// PR integrado é contribuição aceita: entra validado, sem depender de revisão manual.
		assertThat(head.status()).isEqualTo(ContributionStatus.VALIDATED);
		assertThat(contributions.findByRepositoryAndKindAndNumber(repo, ContributionKind.PULL_REQUEST, 101)
			.orElseThrow().reverted()).isTrue();

		// O vínculo tem 1 PR contável; o nível correspondente é concedido automaticamente.
		var seal = seals.findByUserAndType(session.userId(), SealType.AJUDANTE).orElseThrow();
		assertThat(seal.status()).isEqualTo(SealStatus.ACTIVE);
		assertThat(seal.evidenceCount()).isEqualTo(1);

		var second = sync.sync(false, true, List.of(repo));
		assertThat(second.created()).isZero();
		assertThat(second.updated()).isEqualTo(3);
		assertThat(contributions.findByRepositoryAndKindAndNumber(repo, ContributionKind.PULL_REQUEST, 103))
			.isPresent();
	}

	@Test
	void dryRunDoesNotPersist() {
		String suffix = String.valueOf(System.nanoTime());
		String repo = "Rede-ENois/dry-test-" + suffix;
		registry.save(new GitHubRepository(repo, true, Instant.now(), null));
		ISSUES.set(List.of());
		PULLS.set(List.of(pull(201, "gh-201", "dev-201", "PR de teste seco", List.of("src/main/X.java"),
			10, 1, 1, 0, Instant.now())));

		var report = sync.sync(true, true, List.of(repo));
		assertThat(report.created()).isEqualTo(1);
		assertThat(contributions.findByRepositoryAndKindAndNumber(repo, ContributionKind.PULL_REQUEST, 201))
			.isEmpty();
	}

	@Test
	void issuesSyncIsIdempotentAndOnlyQualifiedOnesCount() throws Exception {
		String suffix = String.valueOf(System.nanoTime());
		String repo = "Rede-ENois/issues-test-" + suffix;
		registry.save(new GitHubRepository(repo, true, Instant.now(), null));

		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("gh_issue_" + suffix);
		identities.remember(session.userId(), "gh-issue-" + suffix, "issue-dev");
		Instant now = Instant.now();
		PULLS.set(List.of());
		ISSUES.set(List.of(
			issue(500, "gh-issue-" + suffix, "issue-dev", "Falha no login com 2FA", List.of("qualificado"), now),
			issue(501, "gh-issue-" + suffix, "issue-dev", "Sugestão de texto", List.of(), now)));

		var first = sync.sync(false, true, List.of(repo));
		assertThat(first.issuesCreated()).isEqualTo(2);

		var qualified = contributions.findByRepositoryAndKindAndNumber(repo, ContributionKind.ISSUE, 500)
			.orElseThrow();
		assertThat(qualified.status()).isEqualTo(ContributionStatus.VALIDATED);
		var pending = contributions.findByRepositoryAndKindAndNumber(repo, ContributionKind.ISSUE, 501)
			.orElseThrow();
		assertThat(pending.status()).isEqualTo(ContributionStatus.PENDING);

		assertThat(seals.findByUserAndType(session.userId(), SealType.BETA_TESTER).orElseThrow().status())
			.isEqualTo(SealStatus.ACTIVE);

		var second = sync.sync(false, true, List.of(repo));
		assertThat(second.issuesCreated()).isZero();
		assertThat(second.issuesUpdated()).isEqualTo(2);
	}

	@Test
	void issueThatLosesItsQualificationStopsCounting() throws Exception {
		String suffix = String.valueOf(System.nanoTime());
		String repo = "Rede-ENois/issues-drop-" + suffix;
		registry.save(new GitHubRepository(repo, true, Instant.now(), null));

		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("gh_issue_drop_" + suffix);
		identities.remember(session.userId(), "gh-issue-drop-" + suffix, "issue-dev-drop");
		Instant now = Instant.now();
		PULLS.set(List.of());
		ISSUES.set(List.of(issue(600, "gh-issue-drop-" + suffix, "issue-dev-drop", "Problema real",
			List.of("qualificado"), now)));
		sync.sync(false, true, List.of(repo));
		assertThat(seals.findByUserAndType(session.userId(), SealType.BETA_TESTER).orElseThrow().status())
			.isEqualTo(SealStatus.ACTIVE);

		ISSUES.set(List.of(issue(600, "gh-issue-drop-" + suffix, "issue-dev-drop", "Problema real",
			List.of(), now.plusSeconds(30))));
		sync.sync(false, true, List.of(repo));

		assertThat(contributions.findByRepositoryAndKindAndNumber(repo, ContributionKind.ISSUE, 600)
			.orElseThrow().status()).isEqualTo(ContributionStatus.PENDING);
		assertThat(seals.findByUserAndType(session.userId(), SealType.BETA_TESTER).orElseThrow().status())
			.isEqualTo(SealStatus.REVOKED);
	}

	private static GitHubPullRequest pull(long number, String authorId, String login, String title,
			List<String> files, int additions, int deletions, int reviews, int approvals, Instant now) {
		return new GitHubPullRequest(number, "node-" + number, title, "https://github.com/Rede-ENois/x/pull/" + number,
			"", authorId, login, "closed", now.minusSeconds(3600), now, now, additions, deletions, files.size(),
			files, reviews, approvals, 0);
	}

	private static GitHubPullRequest revert(long number, String authorId, String login, long target, Instant now) {
		return new GitHubPullRequest(number, "node-" + number, "Revert \"PR #" + target + "\"",
			"https://github.com/Rede-ENois/x/pull/" + number, "Reverts #" + target, authorId, login, "closed",
			now.minusSeconds(300), now, now, 2, 1, 1, List.of("src/main/Foo.java"), 0, 0, 0);
	}

	private static GitHubIssue issue(long number, String authorId, String login, String title,
			List<String> labels, Instant now) {
		return new GitHubIssue(number, "node-issue-" + number, title,
			"https://github.com/Rede-ENois/x/issues/" + number, authorId, login, "open", now.minusSeconds(3600),
			null, now, labels);
	}
}
