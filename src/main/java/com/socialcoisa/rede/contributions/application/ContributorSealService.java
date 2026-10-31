package com.socialcoisa.rede.contributions.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.contributions.application.port.out.ContributionAuditRepository;
import com.socialcoisa.rede.contributions.application.port.out.GitHubContributionRepository;
import com.socialcoisa.rede.contributions.application.port.out.GitHubIdentityRepository;
import com.socialcoisa.rede.contributions.application.port.out.GitHubRepositoryRegistry;
import com.socialcoisa.rede.contributions.application.port.out.SealAuditRepository;
import com.socialcoisa.rede.contributions.application.port.out.UserSealRepository;
import com.socialcoisa.rede.contributions.domain.ContributionCategory;
import com.socialcoisa.rede.contributions.domain.ContributionKind;
import com.socialcoisa.rede.contributions.domain.ContributionStatus;
import com.socialcoisa.rede.contributions.domain.GitHubContribution;
import com.socialcoisa.rede.contributions.domain.GitHubRepository;
import com.socialcoisa.rede.contributions.domain.SealStatus;
import com.socialcoisa.rede.contributions.domain.SealType;
import com.socialcoisa.rede.contributions.domain.UserSeal;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Regras de reconhecimento: o nível é derivado da contagem de evidências verificadas; a revisão
 * humana existe para contestar, corrigir e qualificar issues — nunca para o frontend conceder.
 *
 * <p>Cada categoria exibe só o nível mais alto alcançado. Quando a contagem muda (promoção,
 * reversão, issue invalidada), o nível anterior é revogado e o novo concedido, sempre com evento
 * de auditoria. Revogação manual não é desfeita pela sincronização: o administrador precisa
 * conceder de novo explicitamente.</p>
 */
@Service
public class ContributorSealService {

	private final GitHubContributionRepository contributions;
	private final UserSealRepository seals;
	private final SealAuditRepository sealAudit;
	private final ContributionAuditRepository contributionAudit;
	private final GitHubRepositoryRegistry repositories;
	private final GitHubIdentityRepository identities;
	private final UserProfileRepository profiles;
	private final Clock clock;

	public ContributorSealService(GitHubContributionRepository contributions, UserSealRepository seals,
			SealAuditRepository sealAudit, ContributionAuditRepository contributionAudit,
			GitHubRepositoryRegistry repositories, GitHubIdentityRepository identities,
			UserProfileRepository profiles, Clock clock) {
		this.contributions = contributions;
		this.seals = seals;
		this.sealAudit = sealAudit;
		this.contributionAudit = contributionAudit;
		this.repositories = repositories;
		this.identities = identities;
		this.profiles = profiles;
		this.clock = clock;
	}

	@Transactional
	public AdminContributionView review(String contributionId, ContributionStatus target, String note,
			String adminId) {
		if (target == null || target == ContributionStatus.PENDING) {
			throw new ApiException(400, "INVALID_REVIEW_STATUS", "Informe um estado de revisão válido.");
		}
		GitHubContribution contribution = contributions.findById(contributionId)
			.orElseThrow(() -> new ApiException(404, "CONTRIBUTION_NOT_FOUND",
				"Evidência de contribuição não encontrada."));
		if (!allowed(contribution.status(), target)) {
			throw new ApiException(409, "INVALID_STATUS_TRANSITION",
				"Esta evidência não pode passar de " + contribution.status() + " para " + target + ".");
		}
		Instant now = clock.instant();
		String cleanNote = StringUtils.hasText(note) ? note.trim() : null;
		GitHubContribution updated = contributions.save(contribution.withReview(target, cleanNote, adminId, now));
		contributionAudit.append(updated.id(), adminId, target.name(), contribution.status().name(), target.name(),
			cleanNote, now);
		if (updated.userId() != null) {
			recalculate(updated.userId());
		}
		return adminView(updated);
	}

	/**
	 * Recalcula os níveis das categorias implementadas a partir das evidências persistidas.
	 * Idempotente: rodar duas vezes com os mesmos dados não muda o resultado.
	 */
	@Transactional
	public void recalculate(String userId) {
		recalculateCategory(userId, ContributionCategory.CODE);
		recalculateCategory(userId, ContributionCategory.ISSUES);
	}

	@Transactional
	public int recalculateAll() {
		int total = 0;
		for (var identity : identities.findAll()) {
			recalculate(identity.userId());
			total++;
		}
		return total;
	}

	private void recalculateCategory(String userId, ContributionCategory category) {
		int count = contributions.countCountableByUser(userId, category);
		SealType target = SealType.forCount(category, count);
		Instant now = clock.instant();
		for (SealType tier : SealType.of(category)) {
			var existing = seals.findByUserAndType(userId, tier);
			if (tier == target) {
				activate(userId, tier, count, existing, now);
			}
			else if (existing.isPresent() && existing.get().status() == SealStatus.ACTIVE) {
				String reason = count == 0 ? "Sem evidências válidas no momento."
					: "Nível atualizado: agora " + count + " contribuições verificadas.";
				seals.save(existing.get().revoke(now, null, reason));
				sealAudit.append(userId, tier.name(), "REVOKED", null, reason, now);
			}
		}
	}

	private void activate(String userId, SealType tier, int count, java.util.Optional<UserSeal> existing,
			Instant now) {
		if (existing.isPresent()) {
			UserSeal seal = existing.get();
			if (seal.status() == SealStatus.ACTIVE) {
				if (seal.evidenceCount() != count) {
					seals.save(new UserSeal(seal.id(), seal.userId(), seal.sealType(), seal.category(),
						SealStatus.ACTIVE, seal.grantedAt(), seal.grantedBy(), null, null, null, count));
				}
				return;
			}
			// Revogação manual continua valendo até uma concessão explícita da administração.
			if (seal.revokedBy() != null) {
				return;
			}
			seals.save(seal.regrant(now, null, count));
			sealAudit.append(userId, tier.name(), "GRANTED", null,
				"Nível alcançado: " + count + " contribuições verificadas.", now);
			return;
		}
		seals.save(new UserSeal("seal_" + UUID.randomUUID().toString().replace("-", ""), userId, tier,
			tier.category(), SealStatus.ACTIVE, now, null, null, null, null, count));
		sealAudit.append(userId, tier.name(), "GRANTED", null,
			"Nível alcançado: " + count + " contribuições verificadas.", now);
	}

	@Transactional
	public void grantSeal(String userId, SealType type, String reason, String adminId) {
		if (type == null) {
			throw new ApiException(400, "SEAL_TYPE_REQUIRED", "Informe o tipo de selo.");
		}
		Instant now = clock.instant();
		String cleanReason = StringUtils.hasText(reason) ? reason.trim() : "Concessão manual da administração.";
		int count = contributions.countCountableByUser(userId, type.category());
		var existing = seals.findByUserAndType(userId, type);
		if (existing.isPresent() && existing.get().status() == SealStatus.ACTIVE) {
			return;
		}
		UserSeal seal = existing
			.map(item -> item.regrant(now, adminId, count))
			.orElseGet(() -> new UserSeal("seal_" + UUID.randomUUID().toString().replace("-", ""), userId,
				type, type.category(), SealStatus.ACTIVE, now, adminId, null, null, null, count));
		seals.save(seal);
		sealAudit.append(userId, type.name(), "GRANTED", adminId, cleanReason, now);
	}

	@Transactional
	public void revokeSeal(String userId, SealType type, String reason, String adminId) {
		if (type == null) {
			throw new ApiException(400, "SEAL_TYPE_REQUIRED", "Informe o tipo de selo.");
		}
		UserSeal seal = seals.findByUserAndType(userId, type)
			.filter(item -> item.status() == SealStatus.ACTIVE)
			.orElseThrow(() -> new ApiException(404, "SEAL_NOT_ACTIVE", "Este selo não está ativo."));
		Instant now = clock.instant();
		String cleanReason = StringUtils.hasText(reason) ? reason.trim()
			: "Revogação manual pela administração.";
		seals.save(seal.revoke(now, adminId, cleanReason));
		sealAudit.append(userId, type.name(), "REVOKED", adminId, cleanReason, now);
	}

	public List<AdminContributionView> adminList(ContributionStatus status, ContributionKind kind, int limit) {
		return contributions.list(status, kind, Math.max(1, Math.min(limit, 200))).stream()
			.map(this::adminView).toList();
	}

	public AdminContributionDetail adminDetail(String contributionId) {
		GitHubContribution contribution = contributions.findById(contributionId)
			.orElseThrow(() -> new ApiException(404, "CONTRIBUTION_NOT_FOUND",
				"Evidência de contribuição não encontrada."));
		return new AdminContributionDetail(adminView(contribution),
			contributionAudit.findByContribution(contributionId, 100));
	}

	public List<PublicSealView> publicSeals(String username) {
		UserProfile profile = profiles.findByUsername(username)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuário não encontrado."));
		return seals.findActiveByUser(profile.id()).stream()
			.sorted(Comparator.comparing((UserSeal seal) -> seal.category().ordinal()))
			.map(seal -> {
				Integer max = seal.sealType().maxCount() == Integer.MAX_VALUE ? null
					: seal.sealType().maxCount();
				return new PublicSealView(seal.sealType().name(), seal.sealType().displayName(),
					seal.sealType().description(), seal.category().name(),
					seal.category().displayName(), seal.grantedAt(), seal.evidenceCount(),
					seal.sealType().minCount(), max, seal.sealType().emblem());
			}).toList();
	}

	/** Evidências públicas paginadas; o perfil não tem teto fixo de itens. */
	public EvidencePage publicEvidence(String username, ContributionCategory category, int page, int size) {
		UserProfile profile = profiles.findByUsername(username)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuário não encontrado."));
		int safeSize = Math.max(1, Math.min(size, 100));
		int safePage = Math.max(0, page);
		int total = contributions.countCountableByUser(profile.id(), category);
		List<EvidenceView> items = contributions.findCountablePage(profile.id(), category, safePage, safeSize)
			.stream()
			.map(item -> new EvidenceView(item.kind().name(), item.repository(), item.number(), item.title(),
				item.url(), item.mergedAt() != null ? item.mergedAt() : item.closedAt(),
				item.category().name()))
			.toList();
		boolean hasMore = (long) (safePage + 1) * safeSize < total;
		return new EvidencePage(items, total, safePage, safeSize, hasMore);
	}

	public CriteriaView criteria() {
		List<String> repos = repositories.findEnabled().stream().map(GitHubRepository::repository).toList();
		List<CategoryCriteria> categories = Arrays.stream(ContributionCategory.values())
			.filter(ContributionCategory::hasSealTiers)
			.map(category -> new CategoryCriteria(category.name(), category.displayName(),
				category.description(), SealType.of(category).stream()
					.map(tier -> new TierCriteria(tier.name(), tier.displayName(), tier.description(),
						tier.minCount(),
						tier.maxCount() == Integer.MAX_VALUE ? null : tier.maxCount(), tier.emblem()))
					.toList()))
			.toList();
		return new CriteriaView(repos, categories,
			List.of(
				"PR integrado em repositório elegível conta automaticamente; PR revertido não conta.",
				"Issue conta quando tem label de qualificação ou é validada por um mantenedor.",
				"Cada categoria exibe apenas o nível mais alto alcançado.",
				"A pessoa precisa manter o GitHub vinculado à própria conta do Énóis.",
				"Sincronização é periódica: os números podem levar alguns minutos para refletir.",
				"Selo é reconhecimento de trabalho, não privilégio de moderação."),
			List.of("PENDING", "VALIDATED", "REJECTED", "CONTESTED", "REVOKED"));
	}

	public List<RepositoryView> adminRepositories() {
		return repositories.findAll().stream().map(repo -> new RepositoryView(repo.repository(), repo.enabled(),
			repo.lastSyncedAt())).toList();
	}

	@Transactional
	public RepositoryView setRepositoryEnabled(String repository, boolean enabled) {
		GitHubRepository repo = repositories.findAll().stream()
			.filter(item -> item.repository().equals(repository)).findFirst()
			.orElseThrow(() -> new ApiException(404, "GITHUB_REPOSITORY_NOT_FOUND",
				"Repositório não cadastrado."));
		GitHubRepository updated = repositories.save(repo.withEnabled(enabled));
		return new RepositoryView(updated.repository(), updated.enabled(), updated.lastSyncedAt());
	}

	private static boolean allowed(ContributionStatus from, ContributionStatus to) {
		if (from == to) {
			return true;
		}
		return switch (from) {
			case PENDING -> to == ContributionStatus.VALIDATED || to == ContributionStatus.REJECTED
				|| to == ContributionStatus.CONTESTED;
			case VALIDATED -> to == ContributionStatus.REVOKED || to == ContributionStatus.CONTESTED;
			case CONTESTED -> to == ContributionStatus.VALIDATED || to == ContributionStatus.REJECTED
				|| to == ContributionStatus.REVOKED;
			case REJECTED, REVOKED -> to == ContributionStatus.CONTESTED || to == ContributionStatus.VALIDATED;
		};
	}

	private AdminContributionView adminView(GitHubContribution c) {
		String username = c.userId() == null ? null
			: profiles.findById(c.userId()).map(UserProfile::username).orElse(null);
		return new AdminContributionView(c.id(), c.userId(), username, c.githubLogin(), c.repository(),
			c.kind(), c.number(), c.title(), c.url(), c.labels(), c.mergedAt(), c.closedAt(), c.changedFiles(),
			c.additions(), c.deletions(), c.hasTests(), c.reverted(), c.status(), c.score(), c.reviewNote(),
			c.reviewedBy(), c.reviewedAt(), c.createdAt());
	}

	public record PublicSealView(String type, String name, String description, String category,
			String categoryName, Instant grantedAt, int evidenceCount, int minCount, Integer maxCount,
			String emblem) {
	}

	public record EvidencePage(List<EvidenceView> items, int total, int page, int size, boolean hasMore) {
	}

	public record EvidenceView(String kind, String repository, int number, String title, String url,
			Instant happenedAt, String category) {
	}

	public record CriteriaView(List<String> repositories, List<CategoryCriteria> categories,
			List<String> policies, List<String> states) {
	}

	public record CategoryCriteria(String category, String name, String description,
			List<TierCriteria> tiers) {
	}

	public record TierCriteria(String type, String name, String description, int minCount, Integer maxCount,
			String emblem) {
	}

	public record AdminContributionView(String id, String userId, String username, String githubLogin,
			String repository, ContributionKind kind, int number, String title, String url, String labels,
			Instant mergedAt, Instant closedAt, int changedFiles, int additions, int deletions,
			boolean hasTests, boolean reverted, ContributionStatus status, int score, String reviewNote,
			String reviewedBy, Instant reviewedAt, Instant createdAt) {
	}

	public record AdminContributionDetail(AdminContributionView contribution,
			List<ContributionAuditRepository.AuditEvent> audit) {
	}

	public record RepositoryView(String repository, boolean enabled, Instant lastSyncedAt) {
	}
}
