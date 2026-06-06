package com.socialcoisa.rede.network.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/api/network")
class NetworkContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;

	NetworkContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping("/users")
	PageResponse<NetworkUserView> users(@RequestParam(required = false) String query,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		return new PageResponse<>(List.of(), null);
	}

	@GetMapping("/suggestions")
	List<NetworkUserView> suggestions(@RequestParam(defaultValue = "4") int limit) {
		return List.of();
	}

	@GetMapping("/summary")
	NetworkSummaryView summary(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		return new NetworkSummaryView(user.id().value(), 0, 0, 0);
	}

	@GetMapping("/requests")
	PageResponse<ConnectionRequestView> requests(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		currentUserResolver.resolve(userId, sessionToken);
		return new PageResponse<>(List.of(), null);
	}

	@PostMapping("/connections/{targetUserId}")
	ResponseEntity<String> connect(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String targetUserId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("targetUserId", targetUserId));
		return idempotency.execute(user.id(), "POST", "/api/network/connections/{userId}", idempotencyKey, hash,
			() -> new CommandResponse(202, new StatusResponse(targetUserId, "REQUESTED")));
	}

	@PutMapping("/connections/{targetUserId}/accept")
	ResponseEntity<String> accept(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String targetUserId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("targetUserId", targetUserId));
		return idempotency.execute(user.id(), "PUT", "/api/network/connections/{userId}/accept", idempotencyKey, hash,
			() -> new CommandResponse(200, new StatusResponse(targetUserId, "ACCEPTED")));
	}

	@DeleteMapping("/connections/{targetUserId}")
	ResponseEntity<String> remove(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String targetUserId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("targetUserId", targetUserId));
		return idempotency.execute(user.id(), "DELETE", "/api/network/connections/{userId}", idempotencyKey, hash,
			() -> new CommandResponse(200, new StatusResponse(targetUserId, "REMOVED")));
	}

	record NetworkUserView(String id, String username, String name, String headline, String avatar, int connections,
			int followers, boolean connected) {
	}

	record NetworkSummaryView(String userId, int connections, int followers, int pendingRequests) {
	}

	record ConnectionRequestView(String id, String fromUserId, String toUserId, String status, String createdAt) {
	}
}
