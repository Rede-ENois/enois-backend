package com.socialcoisa.rede.studypairs.adapters.in.web;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.StatusResponse;
import com.socialcoisa.rede.studypairs.application.StudyPairService;
import com.socialcoisa.rede.studypairs.domain.PopularGoal;

@RestController
@RequestMapping("/study-pairs")
class StudyPairController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final StudyPairService service;

	StudyPairController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			StudyPairService service) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.service = service;
	}

	@GetMapping("/me")
	StudyPairService.MyStudyPairs me(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return service.mine(currentUserResolver.resolve(sessionToken).id().value());
	}

	@GetMapping("/goals/popular")
	Map<String, List<PopularGoal>> popular(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		currentUserResolver.resolve(sessionToken);
		return Map.of("items", service.popularGoals());
	}

	/** Busca nos pedidos abertos por objetivo/assunto. Resultado anônimo (sem quem pediu). */
	@GetMapping("/search")
	Map<String, List<StudyPairService.SearchResult>> search(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String q, @RequestParam(required = false) Integer limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return Map.of("items", service.search(user.id().value(), q, limit == null ? 20 : limit));
	}

	@PostMapping("/requests")
	ResponseEntity<String> request(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody GoalRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return idempotency.execute(user.id(), "POST", "/api/study-pairs/requests", idempotencyKey,
			idempotency.hash(request),
			() -> new CommandResponse(201, service.request(user.id().value(), request.goal(), request.topic())));
	}

	@DeleteMapping("/requests/current")
	ResponseEntity<String> cancel(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return idempotency.execute(user.id(), "DELETE", "/api/study-pairs/requests/current", idempotencyKey,
			idempotency.hash(Map.of("op", "cancel")),
			() -> new CommandResponse(200,
				new StatusResponse(service.cancelCurrentRequest(user.id().value()), "CANCELLED")));
	}

	@PostMapping("/{pairId}/checkins")
	ResponseEntity<String> checkin(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String pairId, @RequestBody CheckinRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return idempotency.execute(user.id(), "POST", "/api/study-pairs/{pairId}/checkins", idempotencyKey,
			idempotency.hash(Map.of("pairId", pairId, "request", request)),
			() -> new CommandResponse(200, service.checkin(user.id().value(), pairId, request.note())));
	}

	@PostMapping("/{pairId}/end")
	ResponseEntity<String> end(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String pairId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return idempotency.execute(user.id(), "POST", "/api/study-pairs/{pairId}/end", idempotencyKey,
			idempotency.hash(Map.of("pairId", pairId)),
			() -> {
				service.end(user.id().value(), pairId);
				return new CommandResponse(200, new StatusResponse(pairId, "ENDED"));
			});
	}

	record GoalRequest(String goal, String topic) {
	}

	record CheckinRequest(String note) {
	}
}
