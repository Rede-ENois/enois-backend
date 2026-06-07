package com.socialcoisa.rede.communities.adapters.in.web;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import com.socialcoisa.rede.communities.domain.CommunityMember;
import com.socialcoisa.rede.feed.application.FeedPage;
import com.socialcoisa.rede.feed.application.GetFeedService;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;
import java.time.Instant;

@RestController
@RequestMapping("/api/communities")
class CommunityContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final CommunityService communityService;
	private final UserProfileRepository userProfileRepository;
	private final GetFeedService getFeedService;
	private final CursorCodec cursorCodec;

	CommunityContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			CommunityService communityService, UserProfileRepository userProfileRepository,
			GetFeedService getFeedService, CursorCodec cursorCodec) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.communityService = communityService;
		this.userProfileRepository = userProfileRepository;
		this.getFeedService = getFeedService;
		this.cursorCodec = cursorCodec;
	}

	@GetMapping
	PageResponse<CommunityView> communities(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String topic,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = resolveSafely(userId, sessionToken);
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<Community> communities = communityService.list(topic, before, limit);
		var items = communities.stream()
			.map(c -> new CommunityView(c.id(), c.name(), c.description(),
				communityService.countMembers(c.id()), c.topic(), c.cover(), c.avatar(),
				user != null && communityService.isMember(c.id(), user.id().value())))
			.toList();
		String nextCursor = communities.size() == limit
			? cursorCodec.encode(communities.get(communities.size() - 1).createdAt()) : null;
		return new PageResponse<>(items, nextCursor);
	}

	@GetMapping("/{communityId}")
	CommunityView community(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String communityId) {
		CurrentUser user = resolveSafely(userId, sessionToken);
		Community c = communityService.getById(communityId);
		return new CommunityView(c.id(), c.name(), c.description(),
			communityService.countMembers(c.id()), c.topic(), c.cover(), c.avatar(),
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
					request.cover(), request.avatar(), user.id().value());
				return new CommandResponse(201, new CommunityView(c.id(), c.name(), c.description(),
					1, c.topic(), c.cover(), c.avatar(), true));
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
					request.topic(), request.cover(), request.avatar(), user.id().value());
				return new CommandResponse(200, new CommunityView(c.id(), c.name(), c.description(),
					communityService.countMembers(c.id()), c.topic(), c.cover(), c.avatar(),
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

	@GetMapping("/{communityId}/members")
	PageResponse<MemberView> members(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String communityId) {
		resolveSafely(userId, sessionToken);
		List<CommunityMember> members = communityService.findMembers(communityId);
		Set<String> userIds = members.stream().map(CommunityMember::userId).collect(Collectors.toSet());
		Map<String, UserProfile> profiles = userProfileRepository.findAllByIds(userIds).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		var items = members.stream()
			.map(m -> {
				UserProfile p = profiles.get(m.userId());
				return new MemberView(m.userId(),
					p != null ? p.username() : null,
					p != null ? p.name() : null,
					p != null ? p.avatar() : null,
					m.role().name());
			})
			.toList();
		return new PageResponse<>(items, null);
	}

	@GetMapping("/{communityId}/feed")
	FeedPage communityFeed(@PathVariable String communityId,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit,
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = resolveSafely(userId, sessionToken);
		Instant before = cursor != null ? Instant.parse(cursor) : null;
		return getFeedService.getCommunityFeed(communityId, before, limit,
			user != null ? user.id() : new com.socialcoisa.rede.feed.domain.UserId("anonymous"));
	}

	record CommunityView(String id, String name, String description, int members, String topic, String cover,
			String avatar, boolean joined) {
	}

	record CommunityRequest(String name, String description, String topic, String cover, String avatar) {
	}

	record MemberView(String userId, String username, String name, String avatar, String role) {
	}

}
