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
import com.socialcoisa.rede.moderation.application.ModerationService;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

@RestController
@RequestMapping("/api")
class ReportController {

	private final ModerationService moderationService;
	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;

	ReportController(ModerationService moderationService, IdempotencyApplicationService idempotency,
			CurrentUserResolver currentUserResolver) {
		this.moderationService = moderationService;
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
	}

	@PostMapping("/reports")
	ResponseEntity<String> report(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CreateReportRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/reports", idempotencyKey, hash,
			() -> new CommandResponse(201, moderationService.report(user.id().value(),
				request.targetType(), request.targetId(), request.reason(), request.description())));
	}

	@GetMapping("/admin/reports")
	List<ReportView> listPending(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(defaultValue = "20") int limit) {
		currentUserResolver.resolve(userId, sessionToken);
		return moderationService.listPending(limit).stream()
			.map(ReportView::from)
			.toList();
	}

	@PostMapping("/admin/reports/{reportId}/approve")
	ResponseEntity<String> approve(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String reportId, @RequestBody ReviewRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("reportId", reportId, "note", request.note() == null ? "" : request.note()));
		return idempotency.execute(user.id(), "POST", "/api/admin/reports/{reportId}/approve", idempotencyKey, hash,
			() -> new CommandResponse(200, moderationService.approve(reportId, user.id().value(), request.note())));
	}

	@PostMapping("/admin/reports/{reportId}/dismiss")
	ResponseEntity<String> dismiss(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String reportId, @RequestBody ReviewRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("reportId", reportId, "note", request.note() == null ? "" : request.note()));
		return idempotency.execute(user.id(), "POST", "/api/admin/reports/{reportId}/dismiss", idempotencyKey, hash,
			() -> new CommandResponse(200, moderationService.dismiss(reportId, user.id().value(), request.note())));
	}

	record CreateReportRequest(TargetType targetType, String targetId, ReportReason reason, String description) {
	}

	record ReviewRequest(String note) {
	}

	record ReportView(String id, String reporterId, String targetType, String targetId, String reason,
			String description, String status, String createdAt) {
		static ReportView from(Report r) {
			return new ReportView(r.id(), r.reporterId(), r.targetType().name(), r.targetId(), r.reason().name(),
				r.description(), r.status().name(), r.createdAt().toString());
		}
	}
}
