package com.socialcoisa.rede.profile.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.profile.application.RecommendationService;
import com.socialcoisa.rede.profile.domain.Recommendation;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

@RestController
@RequestMapping("/recommendations")
class RecommendationController {

	private final RecommendationService service;
	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;

	RecommendationController(RecommendationService service, IdempotencyApplicationService idempotency,
			CurrentUserResolver currentUserResolver) {
		this.service = service;
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
	}

	@PostMapping("/{targetId}")
	ResponseEntity<String> recommend(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String targetId, @RequestBody(required = false) RecommendBody body) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("targetId", targetId, "comment", body != null ? body.comment() : ""));
		return idempotency.execute(user.id(), "POST", "/api/recommendations/{targetId}", idempotencyKey, hash,
			() -> new CommandResponse(201, RecommendationView.from(service.recommend(user.id().value(), targetId,
				body != null ? body.comment() : null))));
	}

	@DeleteMapping("/{targetId}")
	ResponseEntity<String> remove(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String targetId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("targetId", targetId));
		return idempotency.execute(user.id(), "DELETE", "/api/recommendations/{targetId}", idempotencyKey, hash,
			() -> {
				service.remove(user.id().value(), targetId);
				return new CommandResponse(200, Map.of("status", "REMOVED"));
			});
	}

	@GetMapping("/received/{userId}")
	List<RecommendationView> received(@PathVariable String userId) {
		return service.listReceived(userId).stream().map(RecommendationView::from).toList();
	}

	@GetMapping("/given/{userId}")
	List<RecommendationView> given(@PathVariable String userId) {
		return service.listGiven(userId).stream().map(RecommendationView::from).toList();
	}

	record RecommendBody(String comment) {}

	record RecommendationView(String id, String recommenderId, String recommendedId, String comment, String createdAt) {
		static RecommendationView from(Recommendation r) {
			return new RecommendationView(r.id(), r.recommenderId(), r.recommendedId(), r.comment(),
				r.createdAt().toString());
		}
	}
}
