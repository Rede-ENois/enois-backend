package com.socialcoisa.rede.contributions.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.contributions.application.ContributorSealService;
import com.socialcoisa.rede.contributions.application.GitHubContributionSyncService;
import com.socialcoisa.rede.contributions.domain.ContributionKind;
import com.socialcoisa.rede.contributions.domain.ContributionStatus;
import com.socialcoisa.rede.contributions.domain.SealType;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Operação da fila de selos: revisão de evidência, sincronização e correções administrativas.
 * Nenhuma concessão nasce no frontend; o nível é recalculado a partir do que o sync persistiu.
 */
@RestController
class AdminContributorSealController {

	private final ContributorSealService seals;
	private final GitHubContributionSyncService sync;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminContributorSealController(ContributorSealService seals, GitHubContributionSyncService sync,
			CurrentUserResolver currentUserResolver, AdminGuard adminGuard) {
		this.seals = seals;
		this.sync = sync;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	@GetMapping("/admin/contributors/contributions")
	List<ContributorSealService.AdminContributionView> contributions(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) ContributionStatus status,
			@RequestParam(required = false) ContributionKind kind,
			@RequestParam(defaultValue = "100") int limit) {
		requireAdmin(sessionToken);
		return seals.adminList(status, kind, limit);
	}

	@GetMapping("/admin/contributors/contributions/{id}")
	ContributorSealService.AdminContributionDetail detail(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		requireAdmin(sessionToken);
		return seals.adminDetail(id);
	}

	@PostMapping("/admin/contributors/contributions/{id}/review")
	ContributorSealService.AdminContributionView review(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id, @RequestBody ReviewRequest request) {
		CurrentUser admin = requireAdmin(sessionToken);
		return seals.review(id, request.status(), request.note(), admin.id().value());
	}

	@PostMapping("/admin/contributors/seals/{userId}/revoke")
	java.util.Map<String, String> revoke(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String userId, @RequestBody SealRequest request) {
		CurrentUser admin = requireAdmin(sessionToken);
		seals.revokeSeal(userId, request.type(), request.reason(), admin.id().value());
		return java.util.Map.of("status", "REVOKED");
	}

	@PostMapping("/admin/contributors/seals/{userId}/grant")
	java.util.Map<String, String> grant(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String userId, @RequestBody SealRequest request) {
		CurrentUser admin = requireAdmin(sessionToken);
		seals.grantSeal(userId, request.type(), request.reason(), admin.id().value());
		return java.util.Map.of("status", "ACTIVE");
	}

	@PostMapping("/admin/contributors/sync")
	GitHubContributionSyncService.SyncReport sync(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody SyncRequest request) {
		requireAdmin(sessionToken);
		return sync.sync(request.dryRun(), request.full(), request.repositories());
	}

	@PostMapping("/admin/contributors/users/{userId}/recalculate")
	java.util.Map<String, String> recalculateUser(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String userId) {
		requireAdmin(sessionToken);
		seals.recalculate(userId);
		return java.util.Map.of("status", "RECALCULATED");
	}

	@PostMapping("/admin/contributors/recalculate")
	java.util.Map<String, Integer> recalculateAll(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		requireAdmin(sessionToken);
		return java.util.Map.of("users", seals.recalculateAll());
	}

	@GetMapping("/admin/contributors/repositories")
	List<ContributorSealService.RepositoryView> repositories(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		requireAdmin(sessionToken);
		return seals.adminRepositories();
	}

	@PatchMapping("/admin/contributors/repositories")
	ContributorSealService.RepositoryView updateRepository(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody RepositoryRequest request) {
		requireAdmin(sessionToken);
		return seals.setRepositoryEnabled(request.repository(), request.enabled());
	}

	private CurrentUser requireAdmin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}

	record ReviewRequest(ContributionStatus status, String note) {
	}

	record SealRequest(SealType type, String reason) {
	}

	record SyncRequest(boolean dryRun, boolean full, List<String> repositories) {
	}

	record RepositoryRequest(String repository, boolean enabled) {
	}
}
