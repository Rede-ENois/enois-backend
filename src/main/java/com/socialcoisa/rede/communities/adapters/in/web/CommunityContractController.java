package com.socialcoisa.rede.communities.adapters.in.web;

import java.util.List;
import java.util.Map;

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

import com.socialcoisa.rede.communities.application.CommunityService;
import com.socialcoisa.rede.communities.domain.Community;
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
	private final CommunityService communityService;

	CommunityContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			CommunityService communityService) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.communityService = communityService;
	}

	@GetMapping
	PageResponse<CommunityView> communities(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String topic,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = resolveSafely(userId, sessionToken);
		List<Community> communities = communityService.list(topic, limit);
		var items = communities.stream()
			.map(c -> new CommunityView(c.id(), c.name(), c.description(),
				communityService.countMembers(c.id()), c.topic(), c.cover(),
				user != null && communityService.isMember(c.id(), user.id().value())))
			.toList();
		return new PageResponse<>(items, null);
	}

	@GetMapping("/{communityId}")
	CommunityView community(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String communityId) {
		CurrentUser user = resolveSafely(userId, sessionToken);
		Community c = communityService.getById(communityId);
		return new CommunityView(c.id(), c.name(), c.description(),
			communityService.countMembers(c.id()), c.topic(), c.cover(),
			user != null && communityService.isMember(c.id(), user.id().value()));
	}

	private CurrentUser resolveSafely(String userId, String sessionToken) {
		try {
			return currentUserResolver.resolve(userId, sessionToken);
		} catch (Exception e) {
			return null;
		}
	}

	@PostMapping
	ResponseEntity<String> create(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CommunityRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/communities", idempotencyKey, hash,
			() -> {
				Community c = communityService.create(request.name(), request.description(), request.topic(),
					request.cover(), user.id().value());
				return new CommandResponse(201, new CommunityView(c.id(), c.name(), c.description(),
					1, c.topic(), c.cover(), true));
			});
	}

	@PatchMapping("/{communityId}")
	ResponseEntity<String> update(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId, @RequestBody CommunityRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("communityId", communityId, "request", request));
		return idempotency.execute(user.id(), "PATCH", "/api/communities/{communityId}", idempotencyKey, hash,
			() -> {
				Community c = communityService.update(communityId, request.name(), request.description(),
					request.topic(), request.cover(), user.id().value());
				return new CommandResponse(200, new CommunityView(c.id(), c.name(), c.description(),
					communityService.countMembers(c.id()), c.topic(), c.cover(),
					communityService.isMember(c.id(), user.id().value())));
			});
	}

	@PostMapping("/{communityId}/members")
	ResponseEntity<String> join(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("communityId", communityId));
		return idempotency.execute(user.id(), "POST", "/api/communities/{communityId}/members", idempotencyKey, hash,
			() -> {
				communityService.join(communityId, user.id().value());
				return new CommandResponse(200, new StatusResponse(communityId, "JOINED"));
			});
	}

	@DeleteMapping("/{communityId}/members")
	ResponseEntity<String> leave(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("communityId", communityId));
		return idempotency.execute(user.id(), "DELETE", "/api/communities/{communityId}/members", idempotencyKey, hash,
			() -> {
				communityService.leave(communityId, user.id().value());
				return new CommandResponse(200, new StatusResponse(communityId, "LEFT"));
			});
	}

	record CommunityView(String id, String name, String description, int members, String topic, String cover,
			boolean joined) {
	}

	record CommunityRequest(String name, String description, String topic, String cover) {
	}

}
