package com.socialcoisa.rede.contributions.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.contributions.adapters.out.github.GitHubRateLimitException;
import com.socialcoisa.rede.contributions.application.port.out.ContributionAuditRepository;
import com.socialcoisa.rede.contributions.application.port.out.GitHubContributionRepository;
import com.socialcoisa.rede.contributions.application.port.out.GitHubGateway;
import com.socialcoisa.rede.contributions.application.port.out.GitHubIdentityRepository;
import com.socialcoisa.rede.contributions.application.port.out.GitHubIssue;
import com.socialcoisa.rede.contributions.application.port.out.GitHubPullRequest;
import com.socialcoisa.rede.contributions.application.port.out.GitHubRepositoryRegistry;
import com.socialcoisa.rede.contributions.domain.ContributionCategory;
import com.socialcoisa.rede.contributions.domain.ContributionKind;
import com.socialcoisa.rede.contributions.domain.ContributionStatus;
import com.socialcoisa.rede.contributions.domain.GitHubContribution;
import com.socialcoisa.rede.contributions.domain.GitHubRepository;
import com.socialcoisa.rede.contributions.domain.GitHubSyncState;
import com.socialcoisa.rede.shared.web.ApiException;

import tools.jackson.databind.ObjectMapper;

/**
 * Importa PRs integrados e issues dos repositórios elegíveis e recalcula os selos.
 *
 * <p>A contagem é derivada de evidências persistidas: PR integrado vira evidência validada
 * automaticamente (o GitHub é a fonte); issue só conta quando tem label de qualificação ou quando
 * um mantenedor a valida. Nada disso nasce no frontend. O processo é idempotente por
 * (repositório, tipo, número) e limitado por orçamento de detalhes para não estourar o rate limit.</p>
 */
@Service
public class GitHubContributionSyncService {

	private static final Logger LOG = LoggerFactory.getLogger(GitHubContributionSyncService.class);
	private static final Pattern TEST_PATH = Pattern.compile(
		"(?i)(^|/)(test|tests|__tests__|spec)(/|$)|(\\.test\\.|\\.spec\\.)");
	private static final Pattern REVERT_TARGET = Pattern.compile("#(\\d+)");
	private static final int MAX_ERRORS = 20;

	private final GitHubGateway gateway;
	private final GitHubRepositoryRegistry repositories;
	private final GitHubIdentityRepository identities;
	private final GitHubContributionRepository contributions;
	private final ContributionAuditRepository audit;
	private final ContributorSealService seals;
	private final ObjectMapper json;
	private final Clock clock;
	private final int maxPages;
	private final int maxDetailsPerRun;
	private final Set<String> qualifiedIssueLabels;

	public GitHubContributionSyncService(GitHubGateway gateway, GitHubRepositoryRegistry repositories,
			GitHubIdentityRepository identities, GitHubContributionRepository contributions,
			ContributionAuditRepository audit, ContributorSealService seals, ObjectMapper json, Clock clock,
			@Value("${rede.github.max-pages:3}") int maxPages,
			@Value("${rede.github.max-details-per-run:60}") int maxDetailsPerRun,
			@Value("${rede.github.qualified-issue-labels:}") String qualifiedIssueLabels) {
		this.gateway = gateway;
		this.repositories = repositories;
		this.identities = identities;
		this.contributions = contributions;
		this.audit = audit;
		this.seals = seals;
		this.json = json;
		this.clock = clock;
		this.maxPages = Math.max(1, Math.min(maxPages, 20));
		this.maxDetailsPerRun = Math.max(1, Math.min(maxDetailsPerRun, 200));
		this.qualifiedIssueLabels = parseLabels(qualifiedIssueLabels);
	}

	public SyncReport sync(boolean dryRun, boolean full, List<String> requestedRepositories) {
		Instant started = clock.instant();
		List<GitHubRepository> all = repositories.findAll();
		List<GitHubRepository> selected = select(all, requestedRepositories);

		int fetched = 0;
		int created = 0;
		int updated = 0;
		int skipped = 0;
		int detailsFetched = 0;
		int revertedFlagged = 0;
		int issuesFetched = 0;
		int issuesCreated = 0;
		int issuesUpdated = 0;
		int sealsRecalculated = 0;
		int detailBudget = maxDetailsPerRun;
		boolean rateLimited = false;
		List<String> errors = new ArrayList<>();
		List<String> names = new ArrayList<>();
		Set<String> affectedUsers = new LinkedHashSet<>();

		for (GitHubRepository repository : selected) {
			names.add(repository.repository());
			Set<String> seenGithubIds = new LinkedHashSet<>();
			String failure = null;
			try {
				Instant since = full || repository.lastSyncedAt() == null ? null
					: repository.lastSyncedAt().minus(Duration.ofHours(24));

				List<GitHubPullRequest> pullRequests = gateway.listMergedPullRequests(repository.repository(),
					since, maxPages);
				fetched += pullRequests.size();
				for (GitHubPullRequest pull : pullRequests) {
					seenGithubIds.add(pull.authorId());
					String userId = userIdOf(pull.authorId());
					var existing = contributions.findByRepositoryAndKindAndNumber(repository.repository(),
						ContributionKind.PULL_REQUEST, (int) pull.number());
					boolean needsDetails = existing.isEmpty() || !existing.get().detailsFetched()
						|| !Objects.equals(existing.get().githubUpdatedAt(), pull.updatedAt());
					boolean gotDetails = false;
					GitHubPullRequest detail = pull;
					if (needsDetails && detailBudget > 0) {
						detail = gateway.withDetails(repository.repository(), pull);
						detailBudget--;
						detailsFetched++;
						gotDetails = true;
					}
					else if (needsDetails) {
						skipped++;
					}
					// PR integrado é contribuição aceita: a evidência já nasce validada. Revisão
					// humana continua podendo contestar/revogar depois.
					ContributionStatus status = existing.isEmpty() || existing.get().reviewedBy() == null
						? ContributionStatus.VALIDATED : existing.get().status();
					boolean hasTests = gotDetails ? hasTests(detail.files())
						: existing.map(GitHubContribution::hasTests).orElse(false);
					int additions = detail.additions();
					int deletions = detail.deletions();
					int changedFiles = detail.changedFiles();
					int score = gotDetails ? score(hasTests, detail.approvalsCount(), changedFiles)
						: existing.map(GitHubContribution::score).orElse(0);
					String signals = gotDetails ? signalsJson(hasTests, detail, changedFiles, additions, deletions)
						: existing.map(GitHubContribution::signalsJson).orElse(null);
					String files = gotDetails ? json(detail.files())
						: existing.map(GitHubContribution::filesJson).orElse(null);

					if (existing.isEmpty()) {
						GitHubContribution contribution = new GitHubContribution(
							"ghc_" + UUID.randomUUID().toString().replace("-", ""), userId, detail.authorId(),
							detail.authorLogin(), repository.repository(), ContributionKind.PULL_REQUEST,
							(int) detail.number(), detail.nodeId(), safe(detail.title(), 500),
							safe(detail.url(), 500), detail.state(), detail.openedAt(), detail.mergedAt(), null,
							gotDetails ? detail.updatedAt() : null, gotDetails, additions, deletions, changedFiles,
							files, null, detail.reviewsCount(), detail.approvalsCount(),
							detail.changesRequestedCount(), hasTests, false, ContributionCategory.CODE, status,
							score, signals, null, null, null, started, started);
						if (!dryRun) {
							contributions.save(contribution);
							audit.append(contribution.id(), null, "IMPORTED", null, status.name(),
								"PR #" + detail.number() + " importado de " + repository.repository(), started);
						}
						created++;
					}
					else {
						GitHubContribution current = existing.get();
						GitHubContribution changed = current.withEvidence(userId, safe(detail.title(), 500),
							safe(detail.url(), 500), detail.state(), detail.openedAt(), detail.mergedAt(),
							gotDetails ? detail.updatedAt() : current.githubUpdatedAt(),
							gotDetails || current.detailsFetched(), additions, deletions, changedFiles, files,
							gotDetails ? detail.reviewsCount() : current.reviewsCount(),
							gotDetails ? detail.approvalsCount() : current.approvalsCount(),
							gotDetails ? detail.changesRequestedCount() : current.changesRequestedCount(),
							hasTests, score, signals, status, started);
						if (!dryRun) {
							contributions.save(changed);
						}
						updated++;
					}
					if (userId != null) {
						affectedUsers.add(userId);
					}
				}

				// Revert: um PR com título "Revert ..." e referência #N marca a evidência N.
				for (GitHubPullRequest pull : pullRequests) {
					Integer target = revertTarget(pull.title(), pull.body());
					if (target == null) {
						continue;
					}
					var targetContribution = contributions.findByRepositoryAndKindAndNumber(
						repository.repository(), ContributionKind.PULL_REQUEST, target);
					if (targetContribution.isPresent() && !targetContribution.get().reverted()) {
						if (!dryRun) {
							contributions.save(targetContribution.get().withReverted(true, started));
							audit.append(targetContribution.get().id(), null, "REVERT_DETECTED",
								targetContribution.get().status().name(), targetContribution.get().status().name(),
								"Revertido pelo PR #" + pull.number(), started);
						}
						revertedFlagged++;
					}
				}

				List<GitHubIssue> issues = gateway.listIssues(repository.repository(), since, maxPages);
				issuesFetched += issues.size();
				for (GitHubIssue issue : issues) {
					seenGithubIds.add(issue.authorId());
					String userId = userIdOf(issue.authorId());
					var existing = contributions.findByRepositoryAndKindAndNumber(repository.repository(),
						ContributionKind.ISSUE, (int) issue.number());
					boolean manual = existing.isPresent() && existing.get().reviewedBy() != null;
					ContributionStatus status = manual ? existing.get().status()
						: qualified(issue.labels()) ? ContributionStatus.VALIDATED : ContributionStatus.PENDING;
					// A coluna guarda as labels como JSON curto; truncar evita estourar o varchar.
					String labels = truncate(json(issue.labels()), 1000);
					if (existing.isEmpty()) {
						GitHubContribution contribution = new GitHubContribution(
							"ghc_" + UUID.randomUUID().toString().replace("-", ""), userId, issue.authorId(),
							issue.authorLogin(), repository.repository(), ContributionKind.ISSUE,
							(int) issue.number(), issue.nodeId(), safe(issue.title(), 500), safe(issue.url(), 500),
							issue.state(), issue.openedAt(), null, issue.closedAt(), issue.updatedAt(), false, 0, 0,
							0, null, labels, 0, 0, 0, false, false, ContributionCategory.ISSUES, status, 0,
							labels, null, null, null, started, started);
						if (!dryRun) {
							contributions.save(contribution);
							audit.append(contribution.id(), null, "IMPORTED", null, status.name(),
								"Issue #" + issue.number() + " importada de " + repository.repository(), started);
						}
						issuesCreated++;
					}
					else {
						GitHubContribution changed = existing.get().withIssueEvidence(userId,
							safe(issue.title(), 500), safe(issue.url(), 500), issue.state(), issue.openedAt(),
							issue.closedAt(), issue.updatedAt(), labels, status, started);
						if (!dryRun) {
							contributions.save(changed);
						}
						issuesUpdated++;
					}
					if (userId != null) {
						affectedUsers.add(userId);
					}
				}

				if (!dryRun) {
					repositories.save(repository.withLastSyncedAt(started));
					for (String githubId : seenGithubIds) {
						if (StringUtils.hasText(githubId)) {
							identities.markSynced(githubId, GitHubSyncState.OK, null, started);
						}
					}
				}
			}
			catch (GitHubRateLimitException rate) {
				rateLimited = true;
				failure = rate.getMessage();
				addError(errors, repository.repository() + ": " + rate.getMessage());
				break;
			}
			catch (RuntimeException failureEx) {
				failure = failureEx.getMessage();
				LOG.warn("github_sync_failed repository={} error={}", repository.repository(),
					failureEx.getMessage());
				addError(errors, repository.repository() + ": " + failureEx.getMessage());
				break;
			}
			finally {
				if (failure != null && !dryRun) {
					for (String githubId : seenGithubIds) {
						if (StringUtils.hasText(githubId)) {
							identities.markSynced(githubId, GitHubSyncState.ERROR, failure, started);
						}
					}
				}
			}
		}

		if (!dryRun) {
			for (String userId : affectedUsers) {
				seals.recalculate(userId);
				sealsRecalculated++;
			}
		}

		Instant finished = clock.instant();
		return new SyncReport(dryRun, full, names, fetched, created, updated, skipped, detailsFetched,
			revertedFlagged, issuesFetched, issuesCreated, issuesUpdated, sealsRecalculated, rateLimited,
			List.copyOf(errors), started, finished);
	}

	private String userIdOf(String githubUserId) {
		return StringUtils.hasText(githubUserId)
			? identities.findByGithubUserId(githubUserId).map(identity -> identity.userId()).orElse(null)
			: null;
	}

	private boolean qualified(List<String> labels) {
		if (labels == null || labels.isEmpty() || qualifiedIssueLabels.isEmpty()) {
			return false;
		}
		return labels.stream().filter(StringUtils::hasText)
			.map(label -> label.trim().toLowerCase(Locale.ROOT))
			.anyMatch(qualifiedIssueLabels::contains);
	}

	private static Set<String> parseLabels(String csv) {
		if (!StringUtils.hasText(csv)) {
			return Set.of();
		}
		return Arrays.stream(csv.split(","))
			.map(label -> label.trim().toLowerCase(Locale.ROOT))
			.filter(StringUtils::hasText)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private List<GitHubRepository> select(List<GitHubRepository> all, List<String> requested) {
		if (requested == null || requested.isEmpty()) {
			return all.stream().filter(GitHubRepository::enabled).toList();
		}
		List<GitHubRepository> selected = new ArrayList<>();
		for (String name : requested) {
			GitHubRepository repo = all.stream().filter(item -> item.repository().equals(name)).findFirst()
				.orElseThrow(() -> new ApiException(400, "GITHUB_REPOSITORY_NOT_ELIGIBLE",
					"Repositório não está na lista elegível: " + name));
			if (!repo.enabled()) {
				throw new ApiException(400, "GITHUB_REPOSITORY_DISABLED",
					"Repositório desabilitado para selos: " + name);
			}
			selected.add(repo);
		}
		return selected;
	}

	private static boolean hasTests(List<String> files) {
		return files != null && files.stream().anyMatch(path -> TEST_PATH.matcher(path).find());
	}

	private static int score(boolean hasTests, int approvals, int changedFiles) {
		int score = 0;
		if (hasTests) {
			score += 2;
		}
		if (approvals > 0) {
			score += 1;
		}
		if (changedFiles >= 2) {
			score += 1;
		}
		return score;
	}

	private String signalsJson(boolean hasTests, GitHubPullRequest pr, int changedFiles, int additions,
			int deletions) {
		var signals = new java.util.LinkedHashMap<String, Object>();
		signals.put("tests", hasTests);
		signals.put("approvals", pr.approvalsCount());
		signals.put("changesRequested", pr.changesRequestedCount());
		signals.put("changedFiles", changedFiles);
		signals.put("lines", additions + deletions);
		signals.put("trivial", changedFiles <= 1 && additions + deletions < 5);
		return json(signals);
	}

	private String json(Object value) {
		try {
			return truncate(json.writeValueAsString(value), 20_000);
		}
		catch (Exception failure) {
			return null;
		}
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

	private static String safe(String value, int max) {
		String clean = value == null ? "" : value.replaceAll("\\p{Cntrl}", "").trim();
		return truncate(clean, max);
	}

	private static Integer revertTarget(String title, String body) {
		String text = ((title == null ? "" : title) + " " + (body == null ? "" : body)).toLowerCase(Locale.ROOT);
		if (!text.contains("revert") && !text.contains("reverte")) {
			return null;
		}
		Matcher matcher = REVERT_TARGET.matcher(text);
		return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
	}

	private static void addError(List<String> errors, String message) {
		if (errors.size() < MAX_ERRORS) {
			errors.add(message);
		}
	}

	public record SyncReport(boolean dryRun, boolean full, List<String> repositories, int fetched, int created,
			int updated, int skipped, int detailsFetched, int revertedFlagged, int issuesFetched,
			int issuesCreated, int issuesUpdated, int sealsRecalculated, boolean rateLimited,
			List<String> errors, Instant startedAt, Instant finishedAt) {
	}
}
