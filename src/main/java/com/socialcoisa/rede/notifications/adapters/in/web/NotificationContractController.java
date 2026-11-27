package com.socialcoisa.rede.notifications.adapters.in.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.notifications.adapters.in.web.NotificacoesHttp.NotificationView;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.shared.web.PageResponse;

@RestController
@RequestMapping("/notifications")
class NotificationContractController {

	private final NotificacoesHttp avisos;

	NotificationContractController(NotificacoesHttp avisos) {
		this.avisos = avisos;
	}

	@GetMapping
	PageResponse<NotificationView> notifications(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
			@RequestParam(required = false) String context) {
		return avisos.notifications(sessionToken, cursor, limit, context);
	}

	@GetMapping("/unread-summary")
	NotificationService.UnreadSummary unreadSummary(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String context) {
		return avisos.unreadSummary(sessionToken, context);
	}

	@PostMapping("/{notificationId}/read")
	ResponseEntity<String> read(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String notificationId) {
		return avisos.read(sessionToken, idempotencyKey, notificationId);
	}

	@PostMapping("/read-all")
	ResponseEntity<String> readAll(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		return avisos.readAll(sessionToken, idempotencyKey);
	}
}
