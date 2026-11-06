package com.socialcoisa.rede.groups.adapters.in.web;

import java.time.Instant;
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

import com.socialcoisa.rede.groups.application.GroupService;
import com.socialcoisa.rede.groups.domain.Group;
import com.socialcoisa.rede.groups.domain.GroupJoinMode;
import com.socialcoisa.rede.groups.domain.GroupJoinRequest;
import com.socialcoisa.rede.groups.domain.GroupMember;
import com.socialcoisa.rede.groups.domain.GroupMembership;
import com.socialcoisa.rede.groups.domain.GroupRole;
import com.socialcoisa.rede.groups.domain.GroupStatus;
import com.socialcoisa.rede.groups.domain.JoinOutcome;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.profile.application.NameStyleView;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

/**
 * Endpoints dos Grupos.
 *
 * A leitura pública devolve APENAS a apresentação do grupo (nome, descrição, identidade visual,
 * modo de ingresso) — nunca publicações, membros ou tópicos internos. Quem não é membro vê
 * `joined=false`, `viewerRole=null` e nada além disso, mesmo sabendo o id.
 */
@RestController
@RequestMapping("/groups")
class GroupController {

	private final GroupService groupService;
	private final CurrentUserResolver currentUserResolver;
	private final UserProfileRepository userProfileRepository;
	private final IdempotencyApplicationService idempotency;
	private final CursorCodec cursorCodec;
	private final AdminGuard adminGuard;

	GroupController(GroupService groupService, CurrentUserResolver currentUserResolver,
			UserProfileRepository userProfileRepository, IdempotencyApplicationService idempotency,
			CursorCodec cursorCodec, AdminGuard adminGuard) {
		this.groupService = groupService;
		this.currentUserResolver = currentUserResolver;
		this.userProfileRepository = userProfileRepository;
		this.idempotency = idempotency;
		this.cursorCodec = cursorCodec;
		this.adminGuard = adminGuard;
	}

	@GetMapping
	PageResponse<GroupView> groups(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String query,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		CurrentUser viewer = resolveSafely(sessionToken);
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<Group> groups = groupService.list(query, before, limit);
		var items = groups.stream().map(group -> view(group, viewer)).toList();
		String nextCursor = groups.size() == limit
			? cursorCodec.encode(groups.get(groups.size() - 1).createdAt()) : null;
		return new PageResponse<>(items, nextCursor);
	}

	/** Grupos em que a pessoa participa. Exige sessao: e a lista pessoal, nao o catalogo publico. */
	@GetMapping("/mine")
	PageResponse<GroupView> mine(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "50") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<GroupMembership> memberships = groupService.listMine(user.id().value(), limit);
		Map<String, Long> counts = groupService.countMembers(
			memberships.stream().map(membership -> membership.group().id()).toList());
		var items = memberships.stream()
			.map(membership -> view(membership.group(), counts.getOrDefault(membership.group().id(), 0L),
				membership.role(), false))
			.toList();
		return new PageResponse<>(items, null);
	}

	@GetMapping("/{slug}")
	GroupView group(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String slug) {
		CurrentUser viewer = resolveSafely(sessionToken);
		return view(groupService.resolve(slug), viewer);
	}

	@PostMapping
	ResponseEntity<String> create(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody GroupRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/groups", idempotencyKey, hash, () -> {
			Group group = groupService.create(request.name(), request.description(), request.topic(),
				request.avatar(), request.banner(), request.joinMode(), user.id().value());
			return new CommandResponse(201, view(group, user));
		});
	}

	@PatchMapping("/{groupId}")
	ResponseEntity<String> update(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @RequestBody GroupRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("groupId", groupId, "request", request));
		return idempotency.execute(user.id(), "PATCH", "/api/groups/{groupId}", idempotencyKey, hash, () -> {
			Group group = groupService.update(groupId, request.name(), request.description(), request.topic(),
				request.avatar(), request.banner(), request.bannerHtml(), request.joinMode(), user.id().value());
			return new CommandResponse(200, view(group, user));
		});
	}

	@PostMapping("/{groupId}/join")
	ResponseEntity<String> join(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("groupId", groupId, "action", "join"));
		return idempotency.execute(user.id(), "POST", "/api/groups/{groupId}/join", idempotencyKey, hash, () -> {
			JoinOutcome outcome = groupService.join(groupId, user.id().value());
			return new CommandResponse(200, new JoinResponse(outcome.name()));
		});
	}

	@DeleteMapping("/{groupId}/members/me")
	ResponseEntity<String> leave(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("groupId", groupId, "action", "leave"));
		return idempotency.execute(user.id(), "DELETE", "/api/groups/{groupId}/members/me", idempotencyKey, hash,
			() -> {
				groupService.leave(groupId, user.id().value());
				return new CommandResponse(200, new StatusResponse(groupId, "LEFT"));
			});
	}

	@GetMapping("/{groupId}/members")
	PageResponse<MemberView> members(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String groupId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<GroupMember> members = groupService.listMembers(groupId, user.id().value());
		Map<String, UserProfile> profiles = profilesOf(members.stream().map(GroupMember::userId).toList());
		var items = members.stream().map(member -> memberView(member, profiles.get(member.userId()))).toList();
		return new PageResponse<>(items, null);
	}

	@DeleteMapping("/{groupId}/members/{memberId}")
	ResponseEntity<String> removeMember(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @PathVariable String memberId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("groupId", groupId, "memberId", memberId));
		return idempotency.execute(user.id(), "DELETE", "/api/groups/{groupId}/members/{memberId}",
			idempotencyKey, hash, () -> {
				groupService.removeMember(groupId, user.id().value(), memberId);
				return new CommandResponse(200, new StatusResponse(memberId, "REMOVED"));
			});
	}

	@GetMapping("/{groupId}/requests")
	PageResponse<RequestView> requests(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String groupId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<GroupJoinRequest> requests = groupService.listPendingRequests(groupId, user.id().value());
		Map<String, UserProfile> profiles = profilesOf(requests.stream().map(GroupJoinRequest::userId).toList());
		var items = requests.stream().map(request -> requestView(request, profiles.get(request.userId()))).toList();
		return new PageResponse<>(items, null);
	}

	@PostMapping("/{groupId}/requests/{requestId}/approve")
	ResponseEntity<String> approve(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @PathVariable String requestId) {
		return decide(sessionToken, idempotencyKey, groupId, requestId, true);
	}

	@PostMapping("/{groupId}/requests/{requestId}/reject")
	ResponseEntity<String> reject(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @PathVariable String requestId) {
		return decide(sessionToken, idempotencyKey, groupId, requestId, false);
	}

	@PostMapping("/{groupId}/transfer")
	ResponseEntity<String> transfer(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @RequestBody TransferRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("groupId", groupId, "request", request));
		return idempotency.execute(user.id(), "POST", "/api/groups/{groupId}/transfer", idempotencyKey, hash,
			() -> {
				groupService.transferOwnership(groupId, user.id().value(), request.userId());
				return new CommandResponse(200, new StatusResponse(request.userId(), "OWNER"));
			});
	}

	/**
	 * Estado do grupo pela PLATAFORMA (admin): restrito, suspenso ou reativação. Encerrar é do
	 * dono, no endpoint próprio. A decisão vai para a auditoria com o motivo.
	 */
	@PostMapping("/{groupId}/status")
	ResponseEntity<String> changeStatus(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @RequestBody StatusRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		GroupStatus status = parseStatus(request.status());
		String hash = idempotency.hash(Map.of("groupId", groupId, "request", request));
		return idempotency.execute(user.id(), "POST", "/api/groups/{groupId}/status", idempotencyKey, hash, () -> {
			Group group = groupService.changeStatus(groupId, status, request.reason(), user.id().value());
			return new CommandResponse(200, view(group, user));
		});
	}

	@PostMapping("/{groupId}/close")
	ResponseEntity<String> close(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("groupId", groupId, "action", "close"));
		return idempotency.execute(user.id(), "POST", "/api/groups/{groupId}/close", idempotencyKey, hash, () -> {
			Group group = groupService.close(groupId, user.id().value());
			return new CommandResponse(200, view(group, user));
		});
	}

	private ResponseEntity<String> decide(String sessionToken, String idempotencyKey, String groupId,
			String requestId, boolean approve) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String action = approve ? "approve" : "reject";
		String hash = idempotency.hash(Map.of("groupId", groupId, "requestId", requestId, "action", action));
		return idempotency.execute(user.id(), "POST", "/api/groups/{groupId}/requests/{requestId}/" + action,
			idempotencyKey, hash, () -> {
				GroupJoinRequest decided = groupService.decideRequest(groupId, requestId, user.id().value(), approve);
				return new CommandResponse(200, new JoinResponse(decided.status().name()));
			});
	}

	private GroupView view(Group group, CurrentUser viewer) {
		GroupRole role = viewer == null ? null : groupService.roleOf(group.id(), viewer.id().value());
		boolean pending = viewer != null && role == null
			&& groupService.hasPendingRequest(group.id(), viewer.id().value());
		return view(group, groupService.countMembers(group.id()), role, pending);
	}

	private static GroupView view(Group group, long memberCount, GroupRole role, boolean pending) {
		return new GroupView(group.id(), group.slug(), group.name(), group.description(), group.topic(),
			group.avatar(), group.banner(), group.bannerHtml(), group.joinMode().name(), group.status().name(),
			memberCount, role == null ? null : role.name(), role != null, pending, group.createdAt());
	}

	/** One query for the whole list instead of one per row. */
	private Map<String, UserProfile> profilesOf(List<String> userIds) {
		return userProfileRepository.findAllByIds(Set.copyOf(userIds)).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
	}

	private MemberView memberView(GroupMember member, UserProfile profile) {
		if (profile == null) {
			return new MemberView(member.userId(), null, null, null, member.role().name(), member.joinedAt(), null);
		}
		return new MemberView(member.userId(), profile.username(), profile.name(), profile.avatar(),
			member.role().name(), member.joinedAt(), NameStyleView.from(profile));
	}

	private RequestView requestView(GroupJoinRequest request, UserProfile profile) {
		if (profile == null) {
			return new RequestView(request.id(), request.userId(), null, null, null, request.message(),
				request.createdAt(), null);
		}
		return new RequestView(request.id(), request.userId(), profile.username(), profile.name(),
			profile.avatar(), request.message(), request.createdAt(), NameStyleView.from(profile));
	}

	private CurrentUser resolveSafely(String sessionToken) {
		try {
			return currentUserResolver.resolve(sessionToken);
		} catch (Exception e) {
			return null;
		}
	}

	record GroupRequest(String name, String description, String topic, String avatar, String banner,
			String bannerHtml, GroupJoinMode joinMode) {
	}

	record TransferRequest(String userId) {
	}

	record JoinResponse(String status) {
	}

	record StatusRequest(String status, String reason) {
	}

	private static GroupStatus parseStatus(String status) {
		try {
			return GroupStatus.valueOf(status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException error) {
			throw new com.socialcoisa.rede.shared.web.ApiException(400, "INVALID_STATUS",
				"Estado desconhecido.");
		}
	}

	record GroupView(String id, String slug, String name, String description, String topic, String avatar,
			String banner, String bannerHtml, String joinMode, String status, long members, String viewerRole,
			boolean joined, boolean requestPending, Instant createdAt) {
	}

	record MemberView(String userId, String username, String name, String avatar, String role, Instant joinedAt,
			NameStyleView nameStyle) {
	}

	record RequestView(String id, String userId, String username, String name, String avatar, String message,
			Instant createdAt, NameStyleView nameStyle) {
	}
}
