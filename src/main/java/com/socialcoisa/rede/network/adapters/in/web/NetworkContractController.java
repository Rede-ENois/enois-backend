package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import com.socialcoisa.rede.network.application.NetworkService;
import com.socialcoisa.rede.network.domain.ConnectionSuggestion;
import com.socialcoisa.rede.network.domain.ConnectionStatus;
import com.socialcoisa.rede.network.domain.UserConnection;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/api/network")
class NetworkContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final NetworkService networkService;
	private final UserProfileRepository userProfileRepository;
	private final Clock clock;

	NetworkContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			NetworkService networkService, UserProfileRepository userProfileRepository, Clock clock) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.networkService = networkService;
		this.userProfileRepository = userProfileRepository;
		this.clock = clock;
	}

	@GetMapping("/users")
	PageResponse<NetworkUserView> users(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String query,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		List<UserConnection> connections = networkService.getConnections(user.id().value());
		var items = connections.stream()
			.map(c -> {
				String otherId = c.requesterUserId().equals(user.id().value()) ? c.receiverUserId() : c.requesterUserId();
				return new NetworkUserView(otherId, null, null, null, null, 0, 0, true);
			})
			.toList();
		return new PageResponse<>(items, null);
	}

	@GetMapping("/suggestions")
	List<NetworkUserView> suggestions(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "4") int limit) {
		CurrentUser currentUser = currentUserResolver.resolve(userId, sessionToken);
		List<ConnectionSuggestion> suggestions = networkService.getSuggestions(currentUser.id().value(), limit);
		return suggestions.stream()
			.map(s -> {
				UserProfile profile = userProfileRepository.findById(s.userId()).orElse(null);
				if (profile == null) return null;
				return new NetworkUserView(profile.id(), profile.username(), profile.name(), profile.headline(),
					profile.avatar(), 0, 0, false);
			})
			.filter(v -> v != null)
			.toList();
	}

	@GetMapping("/summary")
	NetworkSummaryView summary(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		int connections = networkService.countConnections(user.id().value());
		int pending = networkService.getPendingRequests(user.id().value()).size();
		return new NetworkSummaryView(user.id().value(), connections, 0, pending);
	}

	@GetMapping("/requests")
	PageResponse<ConnectionRequestView> requests(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		var pending = networkService.getPendingRequests(user.id().value());
		var items = pending.stream()
			.map(c -> new ConnectionRequestView(
				c.id() != null ? c.id().toString() : "",
				c.requesterUserId(),
				c.receiverUserId(),
				c.status().name(),
				c.createdAt() != null ? c.createdAt().toString() : ""))
			.toList();
		return new PageResponse<>(items, null);
	}

	@PostMapping("/connections/{targetUserId}")
	ResponseEntity<String> connect(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String targetUserId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("targetUserId", targetUserId));
		return idempotency.execute(user.id(), "POST", "/api/network/connections/{userId}", idempotencyKey, hash,
			() -> {
				networkService.requestConnection(user.id().value(), targetUserId);
				return new CommandResponse(202, new StatusResponse(targetUserId, "REQUESTED"));
			});
	}

	@PutMapping("/connections/{targetUserId}/accept")
	ResponseEntity<String> accept(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String targetUserId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("targetUserId", targetUserId));
		return idempotency.execute(user.id(), "PUT", "/api/network/connections/{userId}/accept", idempotencyKey, hash,
			() -> {
				networkService.acceptConnection(targetUserId, user.id().value());
				return new CommandResponse(200, new StatusResponse(targetUserId, "ACCEPTED"));
			});
	}

	@DeleteMapping("/connections/{targetUserId}")
	ResponseEntity<String> remove(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String targetUserId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("targetUserId", targetUserId));
		return idempotency.execute(user.id(), "DELETE", "/api/network/connections/{userId}", idempotencyKey, hash,
			() -> {
				networkService.removeConnection(targetUserId, user.id().value());
				return new CommandResponse(200, new StatusResponse(targetUserId, "REMOVED"));
			});
	}

	record NetworkUserView(String id, String username, String name, String headline, String avatar, int connections,
			int followers, boolean connected) {
	}

	record NetworkSummaryView(String userId, int connections, int followers, int pendingRequests) {
	}

	record ConnectionRequestView(String id, String fromUserId, String toUserId, String status, String createdAt) {
	}
}
