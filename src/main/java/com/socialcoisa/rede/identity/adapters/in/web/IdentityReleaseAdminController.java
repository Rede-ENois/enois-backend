package com.socialcoisa.rede.identity.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.identity.application.IdentityReleaseService;
import com.socialcoisa.rede.identity.application.IdentityVerificationService;
import com.socialcoisa.rede.identity.domain.ReleasePolicy;
import com.socialcoisa.rede.identity.domain.ReleaseRound;
import com.socialcoisa.rede.feed.application.port.out.AuditLogRepository;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Liberacao da verificacao de identidade, para a administracao.
 *
 * <p>Nao existe rota para conceder o selo a mao: o selo so nasce do desfecho confirmado pelo
 * provedor. O que a administracao faz aqui e operar a fila (rodada, tamanho, frequencia, pausa) e
 * ler o consumo — e toda intervencao deixa registro em {@code audit_logs}.</p>
 */
@RestController
@RequestMapping("/admin/identity")
class IdentityReleaseAdminController {

	private final IdentityReleaseService releases;
	private final IdentityVerificationService verifications;
	private final AdminGuard adminGuard;
	private final CurrentUserResolver currentUserResolver;
	private final AuditLogRepository auditLogs;

	IdentityReleaseAdminController(IdentityReleaseService releases, IdentityVerificationService verifications,
			AdminGuard adminGuard, CurrentUserResolver currentUserResolver, AuditLogRepository auditLogs) {
		this.releases = releases;
		this.verifications = verifications;
		this.adminGuard = adminGuard;
		this.currentUserResolver = currentUserResolver;
		this.auditLogs = auditLogs;
	}

	@GetMapping("/release")
	OverviewView overview(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		adminGuard.requireAdmin(currentUserResolver.resolve(sessionToken));
		return view(releases.overview());
	}

	@PatchMapping("/release")
	OverviewView update(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody ReleaseSettingsRequest request) {
		CurrentUser admin = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(admin);
		ReleasePolicy current = releases.policy();
		ReleasePolicy next = new ReleasePolicy(
			request.usersPerRound() == null ? current.usersPerRound() : request.usersPerRound(),
			request.intervalMinutes() == null ? current.intervalMinutes() : request.intervalMinutes(),
			request.minReputation() == null ? current.minReputation() : request.minReputation(),
			request.reservedQuotaPercent() == null ? current.reservedQuotaPercent() : request.reservedQuotaPercent(),
			request.maxSimultaneous() == null ? current.maxSimultaneous() : request.maxSimultaneous(),
			request.paused() == null ? current.paused() : request.paused(),
			current.nextRoundAt());
		ReleasePolicy saved = releases.updatePolicy(next);
		auditLogs.record(admin.id(), "identity.release.settings", "didit_release_settings",
			"usersPerRound=" + saved.usersPerRound() + " intervalMinutes=" + saved.intervalMinutes()
				+ " minReputation=" + saved.minReputation() + " reservedQuotaPercent="
				+ saved.reservedQuotaPercent() + " maxSimultaneous=" + saved.maxSimultaneous()
				+ " paused=" + saved.paused());
		return view(releases.overview());
	}

	/** Roda uma rodada agora. Idempotente: repetir nao entrega convite repetido. */
	@PostMapping("/release/rounds")
	RoundView run(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser admin = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(admin);
		ReleaseRound round = releases.runRound(admin.id().value());
		auditLogs.record(admin.id(), "identity.release.round", round.id(),
			"state=" + round.state().name() + " released=" + round.releasedCount() + " eligible="
				+ round.eligibleCount() + " minReputation=" + round.minReputation());
		return round(round);
	}

	@GetMapping("/release/rounds")
	List<RoundView> history(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		adminGuard.requireAdmin(currentUserResolver.resolve(sessionToken));
		return releases.history().stream().map(IdentityReleaseAdminController::round).toList();
	}

	private OverviewView view(IdentityReleaseService.Overview overview) {
		ReleasePolicy policy = overview.policy();
		return new OverviewView(overview.totalUsers(), overview.releasedUsers(), overview.inFlight(),
			overview.startedThisCycle(), overview.remainingCapacity(), overview.estimatedSpentUsd().toPlainString(),
			overview.budgetUsd().toPlainString(), overview.monthlyLimit(),
			new PolicyView(policy.usersPerRound(), policy.intervalMinutes(), policy.minReputation(),
				policy.reservedQuotaPercent(), policy.maxSimultaneous(), policy.paused(),
				policy.nextRoundAt() == null ? null : policy.nextRoundAt().toString()),
			overview.lastRound() == null ? null : round(overview.lastRound()),
			verifications.providerAvailable());
	}

	private static RoundView round(ReleaseRound round) {
		return new RoundView(round.id(), round.executedAt().toString(), round.eligibleCount(),
			round.releasedCount(), round.minReputation(), round.rankStart(), round.rankEnd(),
			round.state().name(), round.reservedCapacityUsed(), round.note());
	}

	record ReleaseSettingsRequest(Integer usersPerRound, Integer intervalMinutes, Integer minReputation,
			Integer reservedQuotaPercent, Integer maxSimultaneous, Boolean paused) {
	}

	record PolicyView(int usersPerRound, int intervalMinutes, int minReputation, int reservedQuotaPercent,
			int maxSimultaneous, boolean paused, String nextRoundAt) {
	}

	record RoundView(String id, String executedAt, int eligibleCount, int releasedCount, int minReputation,
			int rankStart, int rankEnd, String state, int reservedCapacityUsed, String note) {
	}

	record OverviewView(int totalUsers, int releasedUsers, int inFlight, int startedThisCycle,
			int remainingCapacity, String estimatedSpentUsd, String budgetUsd, int monthlyLimit,
			PolicyView policy, RoundView lastRound, boolean providerAvailable) {
	}
}