package com.socialcoisa.rede.contributions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.contributions.application.ContributorSealService;
import com.socialcoisa.rede.contributions.application.GitHubIdentityService;
import com.socialcoisa.rede.contributions.application.port.out.GitHubContributionRepository;
import com.socialcoisa.rede.contributions.application.port.out.UserSealRepository;
import com.socialcoisa.rede.contributions.domain.ContributionCategory;
import com.socialcoisa.rede.contributions.domain.ContributionKind;
import com.socialcoisa.rede.contributions.domain.ContributionStatus;
import com.socialcoisa.rede.contributions.domain.GitHubContribution;
import com.socialcoisa.rede.contributions.domain.SealStatus;
import com.socialcoisa.rede.contributions.domain.SealType;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Níveis derivados da contagem, qualificação de issue e ações administrativas auditáveis. */
@SpringBootTest
@AutoConfigureMockMvc
class ContributorSealFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private GitHubContributionRepository contributions;

	@Autowired
	private UserSealRepository seals;

	@Autowired
	private ContributorSealService sealService;

	@Autowired
	private GitHubIdentityService identities;

	@Autowired
	private UserProfileRepository profiles;

	@Test
	void codeSealMovesUpAndDownWithVerifiedCount() throws Exception {
		String suffix = String.valueOf(System.nanoTime());
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("seal_user_" + suffix);
		identities.remember(user.userId(), "gh-seal-" + suffix, "seal-dev");
		String username = profiles.findById(user.userId()).orElseThrow().username();
		String repo = "Rede-ENois/seal-test-" + suffix;

		savePr(user.userId(), "gh-seal-" + suffix, repo, 900);
		sealService.recalculate(user.userId());
		assertThat(active(user.userId(), SealType.AJUDANTE)).isTrue();

		for (int number = 901; number <= 910; number++) {
			savePr(user.userId(), "gh-seal-" + suffix, repo, number);
		}
		sealService.recalculate(user.userId());
		assertThat(active(user.userId(), SealType.AJUDANTE)).isFalse();
		assertThat(active(user.userId(), SealType.CONTRIBUINTE_SERIO)).isTrue();

		mockMvc.perform(get("/users/" + username + "/seals"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].type").value("CONTRIBUINTE_SERIO"))
			.andExpect(jsonPath("$[0].category").value("CODE"))
			.andExpect(jsonPath("$[0].evidenceCount").value(11))
			.andExpect(jsonPath("$[0].emblem").value("contribuinte-serio"));

		mockMvc.perform(get("/users/" + username + "/seals/CODE/evidence?page=0&size=5"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(11))
			.andExpect(jsonPath("$.items.length()").value(5))
			.andExpect(jsonPath("$.hasMore").value(true))
			.andExpect(jsonPath("$.items[0].kind").value("PULL_REQUEST"));

		mockMvc.perform(get("/users/" + username + "/seals/CODE/evidence?page=2&size=5"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.hasMore").value(false));

		// Um PR revertido deixa de contar e o nível desce.
		var first = contributions.findByRepositoryAndKindAndNumber(repo, ContributionKind.PULL_REQUEST, 900)
			.orElseThrow();
		contributions.save(first.withReverted(true, Instant.now()));
		sealService.recalculate(user.userId());
		assertThat(active(user.userId(), SealType.CONTRIBUINTE_SERIO)).isFalse();
		assertThat(active(user.userId(), SealType.AJUDANTE)).isTrue();
	}

	@Test
	void issueOnlyCountsAfterQualificationAndAdminActionsAreAuditable() throws Exception {
		String suffix = String.valueOf(System.nanoTime());
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("seal_issue_" + suffix);
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("group-admin");
		var regular = new TestAuthHelper(mockMvc, objectMapper).authenticate("seal_regular_" + suffix);
		identities.remember(user.userId(), "gh-issue-seal-" + suffix, "issue-dev");
		String username = profiles.findById(user.userId()).orElseThrow().username();
		String repo = "Rede-ENois/seal-issue-" + suffix;

		String id = saveIssue(user.userId(), "gh-issue-seal-" + suffix, repo, 950,
			ContributionStatus.PENDING);
		sealService.recalculate(user.userId());
		assertThat(seals.findActiveByUser(user.userId())).isEmpty();

		// Não-admin não revisa.
		mockMvc.perform(post("/admin/contributors/contributions/" + id + "/review")
				.header("X-Session-Token", regular.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("status", "VALIDATED", "note", "ok"))))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/contributors/contributions/" + id + "/review")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("status", "VALIDATED",
					"note", "Problema real e bem descrito."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("VALIDATED"));

		assertThat(active(user.userId(), SealType.BETA_TESTER)).isTrue();

		mockMvc.perform(get("/users/" + username + "/seals"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].type").value("BETA_TESTER"))
			.andExpect(jsonPath("$[0].category").value("ISSUES"))
			.andExpect(jsonPath("$[0].evidenceCount").value(1));

		mockMvc.perform(get("/users/" + username + "/seals/ISSUES/evidence"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].kind").value("ISSUE"))
			.andExpect(jsonPath("$.items[0].url").value("https://github.com/Rede-ENois/x/issues/950"));

		MvcResult detail = mockMvc.perform(get("/admin/contributors/contributions/" + id)
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode audit = objectMapper.readTree(detail.getResponse().getContentAsString()).get("audit");
		assertThat(audit.size()).isGreaterThanOrEqualTo(1);

		mockMvc.perform(post("/admin/contributors/seals/" + user.userId() + "/revoke")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "BETA_TESTER",
					"reason", "Revisão administrativa."))))
			.andExpect(status().isOk());
		assertThat(seals.findActiveByUser(user.userId())).isEmpty();

		// Revogação manual não volta sozinha na próxima sincronização.
		sealService.recalculate(user.userId());
		assertThat(seals.findActiveByUser(user.userId())).isEmpty();

		mockMvc.perform(post("/admin/contributors/seals/" + user.userId() + "/grant")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "BETA_TESTER",
					"reason", "Revisão concluída: evidência legítima."))))
			.andExpect(status().isOk());
		assertThat(active(user.userId(), SealType.BETA_TESTER)).isTrue();
	}

	@Test
	void criteriaPublishesBothCategoriesAndEveryBoundary() throws Exception {
		MvcResult result = mockMvc.perform(get("/seals/criteria"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.categories.length()").value(2))
			.andExpect(jsonPath("$.categories[0].category").value("CODE"))
			.andExpect(jsonPath("$.categories[0].tiers.length()").value(5))
			.andExpect(jsonPath("$.categories[0].tiers[0].type").value("AJUDANTE"))
			.andExpect(jsonPath("$.categories[0].tiers[0].minCount").value(1))
			.andExpect(jsonPath("$.categories[0].tiers[0].maxCount").value(10))
			.andExpect(jsonPath("$.categories[0].tiers[0].emblem").value("ajudante"))
			.andExpect(jsonPath("$.categories[0].tiers[4].type").value("GET_A_LIFE"))
			.andExpect(jsonPath("$.categories[0].tiers[4].maxCount").doesNotExist())
			.andExpect(jsonPath("$.categories[1].category").value("ISSUES"))
			.andExpect(jsonPath("$.categories[1].tiers.length()").value(5))
			.andExpect(jsonPath("$.categories[1].tiers[0].type").value("BETA_TESTER"))
			.andExpect(jsonPath("$.categories[1].tiers[4].type").value("GENKIDAMA"))
			.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(body.get("repositories").size()).isGreaterThanOrEqualTo(2);
		assertThat(body.get("policies").size()).isGreaterThanOrEqualTo(4);
	}

	private boolean active(String userId, SealType type) {
		return seals.findByUserAndType(userId, type)
			.map(seal -> seal.status() == SealStatus.ACTIVE).orElse(false);
	}

	private String savePr(String userId, String githubId, String repo, int number) {
		Instant now = Instant.now();
		String id = "ghc_" + UUID.randomUUID().toString().replace("-", "");
		contributions.save(GitHubContribution.builder()
			.id(id).userId(userId).githubUserId(githubId).githubLogin("seal-dev").repository(repo)
			.kind(ContributionKind.PULL_REQUEST).number(number).objectId("node-" + number)
			.title("Corrige cálculo " + number).url("https://github.com/Rede-ENois/x/pull/" + number)
			.state("closed").openedAt(now).mergedAt(now).githubUpdatedAt(now).detailsFetched(true)
			.additions(40).deletions(4).changedFiles(2).filesJson("[\"src/test/BalanceTest.java\"]")
			.reviewsCount(2).approvalsCount(1).changesRequestedCount(0).hasTests(true).reverted(false)
			.category(ContributionCategory.CODE).status(ContributionStatus.VALIDATED).score(3)
			.signalsJson("{\"tests\":true}").createdAt(now).updatedAt(now).build());
		return id;
	}

	private String saveIssue(String userId, String githubId, String repo, int number,
			ContributionStatus status) {
		Instant now = Instant.now();
		String id = "ghc_" + UUID.randomUUID().toString().replace("-", "");
		contributions.save(GitHubContribution.builder()
			.id(id).userId(userId).githubUserId(githubId).githubLogin("issue-dev").repository(repo)
			.kind(ContributionKind.ISSUE).number(number).objectId("node-issue-" + number)
			.title("Falha no login com 2FA").url("https://github.com/Rede-ENois/x/issues/" + number)
			.state("open").openedAt(now).closedAt(null).githubUpdatedAt(now)
			.labels("[\"bug\"]").category(ContributionCategory.ISSUES).status(status).score(0)
			.signalsJson("[\"bug\"]").createdAt(now).updatedAt(now).build());
		return id;
	}
}
