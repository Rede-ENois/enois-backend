package com.socialcoisa.rede.feed.adapters.in.web;

import java.time.Clock;
import java.time.Instant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.feed.application.port.out.FeedSeenMarkerRepository;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * "Voce esta em dia": the front calls this after the person scrolled past the newest posts. The marker
 * only moves forward and never into the future, so a stale tab or a skewed device clock cannot hide
 * posts that have not been seen yet.
 */
@RestController
class FeedSeenController {

	private final FeedSeenMarkerRepository markers;
	private final CurrentUserResolver currentUserResolver;
	private final Clock clock;

	FeedSeenController(FeedSeenMarkerRepository markers, CurrentUserResolver currentUserResolver, Clock clock) {
		this.markers = markers;
		this.currentUserResolver = currentUserResolver;
		this.clock = clock;
	}

	record SeenRequest(Instant seenUntil) {
	}

	@PostMapping("/feed/seen")
	ResponseEntity<Void> seen(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) SeenRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (request == null || request.seenUntil() == null) {
			throw new ApiException(400, "INVALID_SEEN_UNTIL", "Informe até quando o feed foi visto.");
		}
		Instant now = clock.instant();
		Instant seenUntil = request.seenUntil().isAfter(now) ? now : request.seenUntil();
		markers.advance(user.id(), seenUntil, now);
		return ResponseEntity.noContent().build();
	}
}
