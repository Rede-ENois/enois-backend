package com.socialcoisa.rede.notifications.adapters.in.web;

import java.time.Instant;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.notifications.application.NotificationPreferenceService;
import com.socialcoisa.rede.notifications.application.WeeklyDigestService;
import com.socialcoisa.rede.notifications.domain.NotificationPreferences;
import com.socialcoisa.rede.shared.web.ConfirmationPage;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * What reaches the person outside the app:
 *
 * GET /me/notification-preferences — current toggles (defaults: everything on).
 * PUT /me/notification-preferences — partial update (a null or missing field keeps its value).
 * GET /notifications/digest/unsubscribe?token= — opt-out link clicked in the weekly digest e-mail.
 * POST /notifications/digest/unsubscribe?token= — RFC 8058 one-click opt-out sent by the mail client.
 */
@RestController
class NotificationPreferenceController {

	private final NotificationPreferenceService service;
	private final WeeklyDigestService digest;
	private final CurrentUserResolver currentUserResolver;

	NotificationPreferenceController(NotificationPreferenceService service, WeeklyDigestService digest,
			CurrentUserResolver currentUserResolver) {
		this.service = service;
		this.digest = digest;
		this.currentUserResolver = currentUserResolver;
	}

	record PreferencesView(boolean weeklyDigest, boolean pushSocial, boolean pushMessages, boolean pushGroups,
			boolean pushJobs, boolean pushSystem, Instant updatedAt) {

		static PreferencesView of(NotificationPreferences p) {
			return new PreferencesView(p.weeklyDigest(), p.pushSocial(), p.pushMessages(), p.pushGroups(),
				p.pushJobs(), p.pushSystem(), p.updatedAt());
		}
	}

	record UpdateRequest(Boolean weeklyDigest, Boolean pushSocial, Boolean pushMessages, Boolean pushGroups,
			Boolean pushJobs, Boolean pushSystem) {
	}

	@GetMapping("/me/notification-preferences")
	PreferencesView me(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return PreferencesView.of(service.of(userId));
	}

	@PutMapping("/me/notification-preferences")
	PreferencesView update(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) UpdateRequest request) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		UpdateRequest r = request == null ? new UpdateRequest(null, null, null, null, null, null) : request;
		return PreferencesView.of(service.update(userId, r.weeklyDigest(), r.pushSocial(), r.pushMessages(),
			r.pushGroups(), r.pushJobs(), r.pushSystem()));
	}

	@GetMapping(value = "/notifications/digest/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
	ResponseEntity<String> unsubscribe(@RequestParam(required = false) String token) {
		return digest.unsubscribeByToken(token)
			? ConfirmationPage.of(true, "Resumo semanal desligado",
				"Pronto: você não vai mais receber o resumo semanal por e-mail. Se mudar de ideia, dá para religar nas preferências de notificação.")
			: ConfirmationPage.of(false, "Link inválido",
				"Este link não é válido. Entre no Énóis e desligue o resumo semanal nas preferências de notificação.");
	}

	/** RFC 8058: the mail client POSTs "List-Unsubscribe=One-Click" to the List-Unsubscribe URL. */
	@PostMapping("/notifications/digest/unsubscribe")
	ResponseEntity<Void> oneClickUnsubscribe(@RequestParam(required = false) String token) {
		return ResponseEntity.status(digest.unsubscribeByToken(token) ? 200 : 400).build();
	}
}
