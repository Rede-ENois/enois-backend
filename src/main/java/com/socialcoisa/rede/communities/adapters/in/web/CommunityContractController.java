package com.socialcoisa.rede.communities.adapters.in.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/api/communities")
class CommunityContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;

	CommunityContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping
	PageResponse<CommunityView> communities(@RequestParam(required = false) String topic,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		return new PageResponse<>(List.of(), null);
	}

	@GetMapping("/{communityId}")
	CommunityView community(@PathVariable String communityId) {
		return new CommunityView(communityId, "", "", 0, "", null, false);
	}

	@PostMapping
	ResponseEntity<String> create(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CommunityRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/communities", idempotencyKey, hash,
			() -> new CommandResponse(201, new CommunityView(newId("community"), request.name(),
				request.description(), 1, request.topic(), request.cover(), true)));
	}

	@PatchMapping("/{communityId}")
	ResponseEntity<String> update(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId, @RequestBody CommunityRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(new CommunityPayload(communityId, request));
		return idempotency.execute(user.id(), "PATCH", "/api/communities/{communityId}", idempotencyKey, hash,
			() -> new CommandResponse(200, new CommunityView(communityId, request.name(), request.description(), 0,
				request.topic(), request.cover(), false)));
	}

	@PostMapping("/{communityId}/members")
	ResponseEntity<String> join(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("communityId", communityId));
		return idempotency.execute(user.id(), "POST", "/api/communities/{communityId}/members", idempotencyKey, hash,
			() -> new CommandResponse(200, new StatusResponse(communityId, "JOINED")));
	}

	@DeleteMapping("/{communityId}/members")
	ResponseEntity<String> leave(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("communityId", communityId));
		return idempotency.execute(user.id(), "DELETE", "/api/communities/{communityId}/members", idempotencyKey, hash,
			() -> new CommandResponse(200, new StatusResponse(communityId, "LEFT")));
	}

	private String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}

	record CommunityView(String id, String name, String description, int members, String topic, String cover,
			boolean joined) {
	}

	record CommunityRequest(String name, String description, String topic, String cover) {
	}

	record CommunityPayload(String communityId, CommunityRequest request) {
	}
}
