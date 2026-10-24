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

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.moderation.application.ModeratorService;
import com.socialcoisa.rede.moderation.application.SanctionService;
import com.socialcoisa.rede.moderation.domain.Appeal;
import com.socialcoisa.rede.moderation.domain.UserSanction;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Penalidades do proprio usuario (com recurso) e a fila de recursos para o admin.
 * Aprovado = restricao sai; negado = conta banida. Decisao sempre humana.
 */
@RestController
class SanctionController {

	private final SanctionService sanctions;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;
	private final ModeratorService moderators;
	private final AuthSessionRepository sessions;

	SanctionController(SanctionService sanctions, CurrentUserResolver currentUserResolver, AdminGuard adminGuard,
			ModeratorService moderators, AuthSessionRepository sessions) {
		this.sanctions = sanctions;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
		this.moderators = moderators;
		this.sessions = sessions;
	}

	/** Penalidades do proprio usuario (o que a conta carrega e o historico). */
	@GetMapping("/me/sanctions")
	Map<String, Object> mine(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		return Map.of(
			"sanctions", sanctions.sanctionsOf(user.id().value()).stream().map(SanctionView::from).toList(),
			"appeals", sanctions.appealsOf(user.id().value()).stream().map(AppealView::from).toList());
	}

	/** Abre um recurso para a sancao ativa. */
	@PostMapping("/me/sanctions/appeals")
	Map<String, Object> appeal(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody CreateAppealRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		Appeal created = sanctions.appeal(user.id().value(), request.message());
		return Map.of("status", "PENDING", "id", created.id());
	}

	/** Fila de recursos (admin). */
	@GetMapping("/admin/appeals")
	List<AppealView> pending(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "30") int limit) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		return sanctions.pendingAppeals(limit).stream().map(AppealView::from).toList();
	}

	/** Recurso aceito: restricao sai e a conta volta ao normal. */
	@PostMapping("/admin/appeals/{appealId}/approve")
	Map<String, Object> approve(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String appealId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		UserSanction sanction = sanctions.approveAppeal(appealId, user.id().value());
		return Map.of("status", "APPROVED", "sanction", SanctionView.from(sanction));
	}

	/** Recurso negado: conta banida (sessoes revogadas na hora). */
	@PostMapping("/admin/appeals/{appealId}/deny")
	Map<String, Object> deny(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String appealId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(user);
		UserSanction sanction = sanctions.denyAppeal(appealId, user.id().value(), sessions);
		return Map.of("status", "DENIED", "sanction", SanctionView.from(sanction));
	}

	private void requireModerator(CurrentUser user) {
		if (!adminGuard.isAdmin(user) && !moderators.isModerator(user.id().value())) {
			throw new ApiException(403, "FORBIDDEN", "Acesso restrito a moderadores.");
		}
	}

	record CreateAppealRequest(String message) {
	}

	record SanctionView(String id, String userId, String reportId, String type, String reason, String status,
			String createdAt, String removedAt) {
		static SanctionView from(UserSanction s) {
			return new SanctionView(s.id(), s.userId(), s.reportId(), s.type().name(), s.reason(),
				s.status().name(), s.createdAt().toString(),
				s.removedAt() != null ? s.removedAt().toString() : null);
		}
	}

	record AppealView(String id, String sanctionId, String userId, String message, String status, String createdAt,
			String reviewedBy, String reviewedAt) {
		static AppealView from(Appeal a) {
			return new AppealView(a.id(), a.sanctionId(), a.userId(), a.message(), a.status().name(),
				a.createdAt().toString(), a.reviewedBy(),
				a.reviewedAt() != null ? a.reviewedAt().toString() : null);
		}
	}
}
