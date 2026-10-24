package com.socialcoisa.rede.moderation.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.games.application.GameService;
import com.socialcoisa.rede.games.domain.Game;
import com.socialcoisa.rede.moderation.application.BulkBanService;
import com.socialcoisa.rede.moderation.application.ModeratorService;
import com.socialcoisa.rede.moderation.application.SanctionProposalService;
import com.socialcoisa.rede.moderation.application.SuspiciousAutoBanWorker;
import com.socialcoisa.rede.moderation.application.SuspiciousUserService;
import com.socialcoisa.rede.moderation.domain.ModerationVoteType;
import com.socialcoisa.rede.moderation.domain.ModeratorApplicationStatus;
import com.socialcoisa.rede.moderation.domain.SanctionType;
import com.socialcoisa.rede.moderation.domain.SanctionVoteType;
import com.socialcoisa.rede.services.application.ServiceOfferService;
import com.socialcoisa.rede.services.application.ServiceView;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Candidatura a moderador + painel de votação + ações de admin do painel.
 *
 * Permissões:
 * - qualquer sessão: ver o próprio estado e enviar candidatura;
 * - moderador (candidatura aprovada) ou admin: listar denúncias e votar;
 * - admin: revisar candidaturas, revogar moderador e punir qualquer usuário.
 */
@RestController
class ModeratorController {

	private final ModeratorService moderators;
	private final SanctionProposalService sanctionProposals;
	private final SuspiciousUserService suspiciousUsers;
	private final BulkBanService bulkBans;
	private final SuspiciousAutoBanWorker autoBan;
	private final com.socialcoisa.rede.moderation.application.IpBlockService ipBlocks;
	private final com.socialcoisa.rede.moderation.application.port.out.RiskAssessmentReader riskAssessments;
	private final GameService games;
	private final ServiceOfferService serviceOffers;
	private final UserProfileRepository profiles;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	ModeratorController(ModeratorService moderators, SanctionProposalService sanctionProposals,
			SuspiciousUserService suspiciousUsers, BulkBanService bulkBans, SuspiciousAutoBanWorker autoBan,
			com.socialcoisa.rede.moderation.application.IpBlockService ipBlocks,
			com.socialcoisa.rede.moderation.application.port.out.RiskAssessmentReader riskAssessments,
			GameService games, ServiceOfferService serviceOffers, UserProfileRepository profiles,
			CurrentUserResolver currentUserResolver, AdminGuard adminGuard) {
		this.moderators = moderators;
		this.sanctionProposals = sanctionProposals;
		this.suspiciousUsers = suspiciousUsers;
		this.bulkBans = bulkBans;
		this.autoBan = autoBan;
		this.ipBlocks = ipBlocks;
		this.riskAssessments = riskAssessments;
		this.games = games;
		this.serviceOffers = serviceOffers;
		this.profiles = profiles;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	@GetMapping("/moderation/me")
	MeResponse me(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		ModeratorService.MeView me = moderators.me(user.id().value());
		return new MeResponse(me.reputation(), me.minReputation(), me.eligible(), me.moderator(),
			adminGuard.isAdmin(user), me.moderatorCount(), me.minModeratorsForSanction(), me.application());
	}

	@PostMapping("/moderation/applications")
	ModeratorService.ApplicationView apply(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestBody ApplicationRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		return moderators.apply(user.id().value(), request.motivation(), request.experience(),
			request.availability());
	}

	@GetMapping("/moderation/reports")
	List<ModeratorService.PanelReportView> reports(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		return moderators.panelReports(user.id().value(), limit);
	}

	@PostMapping("/moderation/reports/{reportId}/vote")
	ModeratorService.VoteResult vote(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String reportId,
			@RequestBody VoteRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		return moderators.vote(reportId, user.id().value(), request.vote(), request.note());
	}

	@GetMapping("/admin/moderator-applications")
	List<ModeratorService.ApplicationView> applications(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(defaultValue = "PENDING") ModeratorApplicationStatus status,
			@RequestParam(defaultValue = "50") int limit) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		adminGuard.requireAdmin(user);
		return moderators.applicationsOfStatus(status, limit);
	}

	@PostMapping("/admin/moderator-applications/{applicationId}/approve")
	ModeratorService.ApplicationView approve(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String applicationId,
			@RequestBody(required = false) DecisionRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		adminGuard.requireAdmin(user);
		return moderators.decideApplication(applicationId, user.id().value(), true,
			request == null ? null : request.note());
	}

	@PostMapping("/admin/moderator-applications/{applicationId}/reject")
	ModeratorService.ApplicationView reject(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String applicationId,
			@RequestBody(required = false) DecisionRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		adminGuard.requireAdmin(user);
		return moderators.decideApplication(applicationId, user.id().value(), false,
			request == null ? null : request.note());
	}

	@GetMapping("/admin/moderators")
	List<ModeratorService.ApplicationView> moderatorList(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(defaultValue = "200") int limit) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		adminGuard.requireAdmin(user);
		return moderators.approvedModerators(limit);
	}

	@PostMapping("/admin/moderators")
	ModeratorService.ApplicationView appoint(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestBody AppointmentRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		adminGuard.requireAdmin(user);
		return moderators.appoint(request.username(), user.id().value(), request.note());
	}

	@PostMapping("/admin/moderators/{userId}/revoke")
	ModeratorService.ApplicationView revoke(
			@RequestHeader(value = "X-User-Id", required = false) String requesterId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String userId,
			@RequestBody(required = false) DecisionRequest request) {
		CurrentUser user = currentUserResolver.resolve(requesterId, sessionToken);
		adminGuard.requireAdmin(user);
		return moderators.revoke(userId, user.id().value(), request == null ? null : request.note());
	}

	@PostMapping("/admin/users/{username}/sanction")
	ModeratorService.SanctionResult sanction(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String username,
			@RequestBody SanctionRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		return moderators.sanctionUser(user.id().value(), username, request.type(), request.reason());
	}

	/** Limpeza por spam: APAGA DO BANCO todos os posts e comentários do usuário. */
	@PostMapping("/admin/users/{username}/sanitize")
	ModeratorService.SanitizeResult sanitize(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String username,
			@RequestBody(required = false) DecisionRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		return moderators.sanitizeUser(user.id().value(), username,
			request == null ? "Limpeza por spam (motivo não informado)" : request.note());
	}

	/**
	 * Faixa etária declarada (ECA/Lei 15.211): o admin vê o ESTADO e a faixa, nunca a data exata —
	 * data de nascimento é dado sensível e não pertence a painéis de moderação.
	 */
	@GetMapping("/admin/users/{username}/age")
	java.util.Map<String, String> declaredAge(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String username) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		adminGuard.requireAdmin(user);
		var profile = profiles.findByUsername(username.trim())
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuário não encontrado."));
		java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"));
		if (!profile.birthDateDeclared()) {
			return java.util.Map.of("state", "BIRTH_DATE_REQUIRED");
		}
		int age = profile.ageOn(today);
		boolean minor = profile.isMinorOn(today);
		return java.util.Map.of(
			"state", minor ? "MINOR_DECLARED" : "ADULT_DECLARED",
			"ageBand", minor ? (age <= 13 ? "ATE_13" : "14_17") : "ADULTO");
	}

	/**
	 * O histórico de risco da conta: o que o motor de risco combinou, com nível, sinais e versão da
	 * regra. É o que responde "por que esta pessoa foi sinalizada?" — a decisão do motor fica
	 * reconstruível, sem depender de memória de ninguém.
	 */
	@GetMapping("/admin/users/{username}/risco")
	List<com.socialcoisa.rede.shared.risk.RiskAssessment> riskHistory(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String username,
			@RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		adminGuard.requireAdmin(user);
		var profile = profiles.findByUsername(username.trim())
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuário não encontrado."));
		return riskAssessments.recentFor(profile.id(), Math.min(100, Math.max(1, limit)));
	}

	/** IPs recentes de onde o usuário entrou — fonte para o bloqueio de IP. */
	@GetMapping("/admin/users/{username}/ips")
	List<com.socialcoisa.rede.moderation.application.IpBlockService.UserIpView> userIps(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String username) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		return ipBlocks.ipsOfUser(username);
	}

	@PostMapping("/admin/blocked-ips")
	com.socialcoisa.rede.moderation.application.IpBlockService.BlockedIpView blockIp(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestBody BlockIpRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		return ipBlocks.block(user.id().value(), request.ip(), request.reason());
	}

	@GetMapping("/admin/blocked-ips")
	List<com.socialcoisa.rede.moderation.application.IpBlockService.BlockedIpView> blockedIps(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(defaultValue = "100") int limit) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		return ipBlocks.list(limit);
	}

	@PostMapping("/admin/blocked-ips/{ip}/unblock")
	Map<String, String> unblockIp(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String ip) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		ipBlocks.unblock(ip);
		return Map.of("ip", ip, "blocked", "false");
	}

	public record BlockIpRequest(String ip, String reason) {
	}

	/**
	 * Contas recentes ranqueadas por sinais objetivos (nome em série, e-mail não confirmado,
	 * perfil vazio, denúncias contra). Não bane ninguém: é a fila de validação humana.
	 *
	 * Fica em /moderation (e não em /admin) porque a limpeza de contas em série é trabalho do
	 * time de moderação — qualquer moderador aprovado vê a fila e pode agir (decisão da dona,
	 * 2026-10-22).
	 */
	@GetMapping("/moderation/users/suspicious")
	SuspiciousUserService.SuspiciousUsersView suspicious(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(defaultValue = "100") int limit,
			@RequestParam(defaultValue = "30") int days) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		return suspiciousUsers.list(limit, days);
	}

	/** Banimento em lote: cada conta passa pelo fluxo normal de sanção, com resultado por item. */
	@PostMapping("/moderation/users/bulk-ban")
	BulkBanService.BulkBanResult bulkBan(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestBody BulkBanRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		return bulkBans.ban(user.id().value(), request.usernames(), request.type(), request.reason());
	}

	/** Última rodada da automação (o "não sabemos o que aconteceu" do incidente). */
	@GetMapping("/admin/automod/status")
	SuspiciousAutoBanWorker.Status automodStatus(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		adminGuard.requireAdmin(user);
		return autoBan.status();
	}

	@GetMapping("/moderation/sanction-proposals")
	List<SanctionProposalService.ProposalView> sanctionProposals(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(defaultValue = "50") int limit) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		return sanctionProposals.pending(user.id().value(), limit);
	}

	@PostMapping("/moderation/sanction-proposals")
	SanctionProposalService.ProposalView proposeSanction(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestBody SanctionProposalRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		return sanctionProposals.propose(user.id().value(), request.username(), request.type(), request.reason());
	}

	@PostMapping("/moderation/sanction-proposals/{proposalId}/vote")
	SanctionProposalService.DecisionResult voteSanction(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String proposalId,
			@RequestBody SanctionProposalVoteRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		return sanctionProposals.vote(proposalId, user.id().value(), request.vote(), request.note());
	}

	@PostMapping("/admin/sanction-proposals/{proposalId}/cancel")
	SanctionProposalService.ProposalView cancelSanction(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String proposalId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		adminGuard.requireAdmin(user);
		return sanctionProposals.cancel(proposalId, user.id().value());
	}

	@GetMapping("/moderation/games")
	List<GameReviewView> pendingGames(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(defaultValue = "30") int limit) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		return games.pending(limit).stream().map(this::gameView).toList();
	}

	@PostMapping("/moderation/games/{gameId}/approve")
	Map<String, Object> approveGame(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String gameId,
			@RequestBody(required = false) DecisionRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		if (!adminGuard.isAdmin(user)) {
			games.requireNotOwnSubmission(gameId, user.id().value());
		}
		Game approved = games.approve(gameId, user.id().value(), request == null ? null : request.note());
		return Map.of("id", approved.id(), "status", approved.status().name());
	}

	@PostMapping("/moderation/games/{gameId}/reject")
	Map<String, Object> rejectGame(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String gameId,
			@RequestBody(required = false) DecisionRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		Game rejected = games.reject(gameId, user.id().value(), request == null ? null : request.note());
		return Map.of("id", rejected.id(), "status", rejected.status().name());
	}

	// ---- Serviços da comunidade: mesma fila e mesmas regras dos jogos ----

	private GameReviewView gameView(Game game) {
		String username = profiles.findById(game.userId()).map(profile -> profile.username()).orElse(null);
		return new GameReviewView(game.id(), game.userId(), username, game.title(), game.description(),
			game.cover(), game.url(), game.tags(), game.aspect() == null ? null : game.aspect().name(),
			game.submittedAt() == null ? null : game.submittedAt().toString());
	}

	private void requireModerator(CurrentUser user) {
		if (!adminGuard.isAdmin(user) && !moderators.isModerator(user.id().value())) {
			throw new ApiException(403, "FORBIDDEN", "Acesso restrito a moderadores.");
		}
	}

	record MeResponse(int reputation, int minReputation, boolean eligible, boolean moderator,
			boolean admin, long moderatorCount, int minModeratorsForSanction,
			ModeratorService.ApplicationView application) {
	}

	record ApplicationRequest(String motivation, String experience, String availability) {
	}

	// ---- Serviços da comunidade: mesma fila e mesmas regras dos jogos ----

	@GetMapping("/moderation/services")
	List<ServiceView> pendingServices(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(defaultValue = "30") int limit) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		return serviceOffers.pending(limit).stream()
			.map(offer -> ServiceView.of(offer, serviceOffers.usernameOf(offer), user.id().value()))
			.toList();
	}

	@PostMapping("/moderation/services/{serviceId}/approve")
	Map<String, Object> approveService(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String serviceId,
			@RequestBody(required = false) DecisionRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		if (!adminGuard.isAdmin(user)) {
			serviceOffers.requireNotOwnSubmission(serviceId, user.id().value());
		}
		var approved = serviceOffers.approve(serviceId, user.id().value(),
			request == null ? null : request.note());
		return Map.of("id", approved.id(), "status", approved.status().name());
	}

	@PostMapping("/moderation/services/{serviceId}/reject")
	Map<String, Object> rejectService(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String serviceId,
			@RequestBody DecisionRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		var rejected = serviceOffers.reject(serviceId, user.id().value(),
			request == null ? null : request.note());
		// O motivo vai junto na resposta: é o que o painel mostra e o prestador vai ler.
		return Map.of("id", rejected.id(), "status", rejected.status().name(), "reviewNote",
			rejected.reviewNote() == null ? "" : rejected.reviewNote());
	}

	@PostMapping("/moderation/services/{serviceId}/suspend")
	Map<String, Object> suspendService(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String serviceId,
			@RequestBody(required = false) DecisionRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		var suspended = serviceOffers.suspend(serviceId, user.id().value(),
			request == null ? null : request.note());
		return Map.of("id", suspended.id(), "status", suspended.status().name());
	}

	record DecisionRequest(String note) {
	}

	record AppointmentRequest(String username, String note) {
	}

	record VoteRequest(ModerationVoteType vote, String note) {
	}

	record SanctionRequest(SanctionType type, String reason) {
	}

	record BulkBanRequest(List<String> usernames, SanctionType type, String reason) {
	}

	record SanctionProposalRequest(String username, SanctionType type, String reason) {
	}

	record SanctionProposalVoteRequest(SanctionVoteType vote, String note) {
	}

	record GameReviewView(String id, String userId, String username, String title, String description,
			String cover, String url, String tags, String aspect, String submittedAt) {
	}
}
