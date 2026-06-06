package com.socialcoisa.rede.notifications.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.notifications.domain.Notification;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/api/notifications")
class NotificationContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final NotificationService notificationService;

	NotificationContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			NotificationService notificationService) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.notificationService = notificationService;
	}

	@GetMapping
	PageResponse<NotificationView> notifications(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		List<Notification> notifications = notificationService.list(user.id().value(), limit);
		var items = notifications.stream()
			.map(n -> new NotificationView(n.id(), n.kind(), n.actorId(), n.title(), n.body(),
				n.createdAt() != null ? n.createdAt().toString() : null, n.isRead(), n.href(), n.ctaLabel()))
			.toList();
		return new PageResponse<>(items, null);
	}

	@PostMapping("/{notificationId}/read")
	ResponseEntity<String> read(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String notificationId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("notificationId", notificationId));
		return idempotency.execute(user.id(), "POST", "/api/notifications/{notificationId}/read", idempotencyKey, hash,
			() -> {
				notificationService.markRead(notificationId, user.id().value());
				return new CommandResponse(200, new StatusResponse(notificationId, "READ"));
			});
	}

	@PostMapping("/read-all")
	ResponseEntity<String> readAll(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("userId", user.id().value()));
		return idempotency.execute(user.id(), "POST", "/api/notifications/read-all", idempotencyKey, hash,
			() -> {
				notificationService.markAllRead(user.id().value());
				return new CommandResponse(200, new StatusResponse(user.id().value(), "ALL_READ"));
			});
	}

	record NotificationView(String id, String kind, String actorId, String title, String body, String createdAt,
			boolean read, String href, String ctaLabel) {
	}

}
