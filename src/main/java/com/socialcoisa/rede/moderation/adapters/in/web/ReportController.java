package com.socialcoisa.rede.moderation.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.moderation.application.ModerationService;
import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

@RestController
class ReportController {

	private final ModerationService moderationService;
	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;
	private final ReportRepository reports;
	private final ModerationRemovePostUseCase removePost;
	private final com.socialcoisa.rede.moderation.application.ModeratorService moderators;
	private final com.socialcoisa.rede.moderation.adapters.out.persistence.ReportE2eKeyStore reportE2eKeys;
	private final com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository sessions;

	ReportController(ModerationService moderationService, IdempotencyApplicationService idempotency,
			CurrentUserResolver currentUserResolver, AdminGuard adminGuard, ReportRepository reports,
			ModerationRemovePostUseCase removePost,
			com.socialcoisa.rede.moderation.application.ModeratorService moderators,
			com.socialcoisa.rede.moderation.adapters.out.persistence.ReportE2eKeyStore reportE2eKeys,
			com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository sessions) {
		this.moderationService = moderationService;
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
		this.reports = reports;
		this.removePost = removePost;
		this.moderators = moderators;
		this.reportE2eKeys = reportE2eKeys;
		this.sessions = sessions;
	}

	@PostMapping("/reports")
	ResponseEntity<String> report(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CreateReportRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/reports", idempotencyKey, hash,
			() -> {
				var report = moderationService.report(user.id().value(),
					request.targetType(), request.targetId(), request.reason(), request.description());
				if (request.e2eConversationKey() != null && !request.e2eConversationKey().isBlank()
						&& request.e2eConversationKey().length() <= 8192) {
					reportE2eKeys.save(report.id(), request.e2eConversationKey());
				}
				return new CommandResponse(201, ReportView.from(report));
			});
	}

	/** Denuncias do proprio usuario (acompanhamento; sem dados de terceiros nem triagem interna). */
	@GetMapping("/reports/mine")
	List<ReporterReportView> mine(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return moderationService.reportsOf(user.id().value(), 100).stream().map(ReporterReportView::from).toList();
	}

	/** Chave E2E anexada a uma denuncia de mensagem (moderador decifra no browser). */
	@GetMapping("/admin/reports/{reportId}/e2e-key")
	java.util.Map<String, String> reportE2eKey(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String reportId) {
		adminGuard.requireAdmin(currentUserResolver.resolve(sessionToken));
		return reportE2eKeys.findByReportId(reportId)
			.map(key -> java.util.Map.of("conversationKey", key))
			.orElse(java.util.Map.of());
	}

	@GetMapping("/admin/reports")
	List<ReportView> listPending(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(defaultValue = "20") int limit) {
		adminGuard.requireAdmin(currentUserResolver.resolve(sessionToken));
		return moderationService.listPending(limit).stream()
			.map(ReportView::from)
			.toList();
	}

	/** Retrato completo de uma denuncia para o painel de revisão aberto pelo e-mail. */
	@GetMapping("/admin/reports/{reportId}")
	Map<String, Object> review(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String reportId) {
		adminGuard.requireAdmin(currentUserResolver.resolve(sessionToken));
		var review = moderationService.review(reportId);
		return Map.of(
			"report", ReportView.from(review.report()),
			"reporter", review.reporter(),
			"authorId", review.authorId() == null ? "" : review.authorId(),
			"author", review.author() == null ? "" : review.author(),
			"content", review.content() == null ? "" : review.content());
	}

	/**
	 * Posts denunciados com score de pré-triagem alto: fila pronta para exclusão em lote.
	 *
	 * Em /moderation (e não em /admin) porque a exclusão de post denunciado é trabalho do time de
	 * moderação — qualquer moderador aprovado vê a fila e age (decisão da dona, 2026-10-22).
	 */
	@GetMapping("/moderation/posts/suspicious")
	List<SuspiciousPostView> suspiciousPosts(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "30") int minScore,
			@RequestParam(defaultValue = "50") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		requireModerator(user);
		return reports.findPendingPostsByPreTriageScore(Math.max(0, minScore), Math.min(200, Math.max(1, limit)))
			.stream()
			.map(report -> new SuspiciousPostView(report.targetId(), report.id(),
				report.preTriageScore() == null ? 0 : report.preTriageScore(), report.reason().name(),
				report.preTriage()))
			.toList();
	}

	/** Exclusão (soft delete com auditoria) dos posts suspeitos selecionados. */
	@PostMapping("/moderation/posts/bulk-delete")
	BulkDeletePostsResult bulkDeleteSuspiciousPosts(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody BulkDeleteSuspiciousRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		requireModerator(user);
		if (request == null || request.postIds() == null || request.postIds().isEmpty()) {
			throw new com.socialcoisa.rede.shared.web.ApiException(400, "NO_POSTS_SELECTED",
				"Selecione pelo menos um post.");
		}
		if (request.reason() == null || request.reason().trim().length() < 10) {
			throw new com.socialcoisa.rede.shared.web.ApiException(400, "REASON_REQUIRED",
				"Explique o motivo da exclusão (pelo menos 10 caracteres).");
		}
		List<String> ids = request.postIds().stream().filter(java.util.Objects::nonNull)
			.map(String::trim).filter(id -> !id.isEmpty()).distinct().limit(100).toList();
		List<BulkDeletePostItem> items = ids.stream()
			.map(id -> {
				try {
					removePost.removeAsModerator(user.id().value(), id, "ADMIN:" + request.reason().trim());
					return new BulkDeletePostItem(id, "DELETED");
				}
				catch (RuntimeException failure) {
					return new BulkDeletePostItem(id, "FAILED");
				}
			})
			.toList();
		return new BulkDeletePostsResult(items.size(), items);
	}

	@PostMapping("/admin/reports/{reportId}/penalize")
	ResponseEntity<String> penalize(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String reportId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return ResponseEntity.ok(ReportView.from(moderationService.penalize(reportId, user.id().value())).toString());
	}

	@PostMapping("/admin/reports/{reportId}/warn")
	ResponseEntity<String> warn(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String reportId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return ResponseEntity.ok(ReportView.from(moderationService.warn(reportId, user.id().value())).toString());
	}

	@PostMapping("/admin/reports/{reportId}/ban")
	ResponseEntity<String> ban(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String reportId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return ResponseEntity.ok(ReportView.from(moderationService.ban(reportId, user.id().value(), this.sessions)).toString());
	}

	/** Remove a pagina empresarial denunciada (fecha a pagina; nao toca a conta do dono). */
	@PostMapping("/admin/reports/{reportId}/remove-company")
	ResponseEntity<String> removeCompany(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String reportId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		String hash = idempotency.hash(Map.of("reportId", reportId, "op", "remove-company"));
		return idempotency.execute(user.id(), "POST", "/api/admin/reports/{reportId}/remove-company",
			idempotencyKey, hash,
			() -> new CommandResponse(200, ReportView.from(moderationService.removeCompany(
				reportId, user.id().value()))));
	}

	@PostMapping("/admin/reports/{reportId}/approve")
	ResponseEntity<String> approve(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String reportId, @RequestBody ReviewRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		String hash = idempotency.hash(Map.of("reportId", reportId, "note", request.note() == null ? "" : request.note()));
		return idempotency.execute(user.id(), "POST", "/api/admin/reports/{reportId}/approve", idempotencyKey, hash,
			() -> new CommandResponse(200, ReportView.from(
				moderationService.approve(reportId, user.id().value(), request.note()))));
	}

	@PostMapping("/admin/reports/{reportId}/dismiss")
	ResponseEntity<String> dismiss(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String reportId, @RequestBody ReviewRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		String hash = idempotency.hash(Map.of("reportId", reportId, "note", request.note() == null ? "" : request.note()));
		return idempotency.execute(user.id(), "POST", "/api/admin/reports/{reportId}/dismiss", idempotencyKey, hash,
			() -> new CommandResponse(200, ReportView.from(
				moderationService.dismiss(reportId, user.id().value(), request.note()))));
	}

	record CreateReportRequest(TargetType targetType, String targetId, ReportReason reason, String description,
			String e2eConversationKey) {
	}

	record SuspiciousPostView(String postId, String reportId, int score, String reason, String signals) {
	}

	record BulkDeleteSuspiciousRequest(List<String> postIds, String reason) {
	}

	record BulkDeletePostItem(String postId, String status) {
	}

	record BulkDeletePostsResult(int total, List<BulkDeletePostItem> items) {
	}

	record ReviewRequest(String note) {
	}

	/** Visao do denunciante: sem reporterId (e ele mesmo) e sem aiTriage (uso interno). */
	record ReporterReportView(String id, String targetType, String targetId, String reason,
			String description, String status, String createdAt) {
		static ReporterReportView from(Report r) {
			return new ReporterReportView(r.id(), r.targetType().name(), r.targetId(), r.reason().name(),
				r.description(), r.status().name(), r.createdAt().toString());
		}
	}

	record ReportView(String id, String reporterId, String targetType, String targetId, String reason,
			String description, String status, String createdAt, String aiTriage, String aiTriagedAt) {
		static ReportView from(Report r) {
			return new ReportView(r.id(), r.reporterId(), r.targetType().name(), r.targetId(), r.reason().name(),
				r.description(), r.status().name(), r.createdAt().toString(),
				r.aiTriage(), r.aiTriagedAt() != null ? r.aiTriagedAt().toString() : null);
		}
	}

	/** Admin ou moderador aprovado: os dois enxergam a limpeza de conteúdo denunciado. */
	private void requireModerator(CurrentUser user) {
		if (!adminGuard.isAdmin(user) && !moderators.isModerator(user.id().value())) {
			throw new com.socialcoisa.rede.shared.web.ApiException(403, "FORBIDDEN",
				"Acesso restrito a moderadores.");
		}
	}
}
