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

import com.socialcoisa.rede.communities.application.CommunityChannelService;
import com.socialcoisa.rede.communities.application.CommunityService;
import com.socialcoisa.rede.communities.domain.Community;
import com.socialcoisa.rede.communities.domain.CommunityMember;
import com.socialcoisa.rede.feed.application.FeedPage;
import com.socialcoisa.rede.feed.application.GetFeedService;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.profile.application.NameStyleView;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;
import java.time.Instant;

@RestController
@RequestMapping("/communities")
class CommunityContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;

	private final com.socialcoisa.rede.shared.web.AdminGuard adminGuard;
	private final CommunityService communityService;
	private final UserProfileRepository userProfileRepository;
	private final GetFeedService getFeedService;
	private final com.socialcoisa.rede.communities.application.CommunityChannelService channelService;
	private final CursorCodec cursorCodec;

	CommunityContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			CommunityService communityService, UserProfileRepository userProfileRepository,
			GetFeedService getFeedService, CursorCodec cursorCodec,
			com.socialcoisa.rede.communities.application.CommunityChannelService channelService,
			com.socialcoisa.rede.shared.web.AdminGuard adminGuard) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.communityService = communityService;
		this.userProfileRepository = userProfileRepository;
		this.getFeedService = getFeedService;
		this.channelService = channelService;
		this.cursorCodec = cursorCodec;
		this.adminGuard = adminGuard;
	}

	@GetMapping
	PageResponse<CommunityView> communities(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String topic, @RequestParam(required = false) String q,
			@RequestParam(required = false) String city, @RequestParam(required = false) String state,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = resolveSafely(sessionToken);
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<Community> communities = communityService.list(topic, q, city, state, before, limit);
		// Duas consultas para a pagina inteira (contagem e "sou membro"), nao quatro por item: com 20
		// comunidades a antiga vitrine disparava ~40 consultas identicas so para desenhar a lista.
		java.util.Map<String, Integer> memberCounts = communityService
			.countMembersByCommunityIds(communities.stream().map(Community::id).toList());
		java.util.Set<String> myCommunities = user == null ? java.util.Set.of()
			: communityService.memberCommunityIds(user.id().value(),
				communities.stream().map(Community::id).toList());
		var items = communities.stream()
			.map(c -> view(c, memberCounts.getOrDefault(c.id(), 0), myCommunities.contains(c.id())))
			.toList();
		String nextCursor = communities.size() == limit
			? cursorCodec.encode(communities.get(communities.size() - 1).createdAt()) : null;
		return new PageResponse<>(items, nextCursor);
	}

	@GetMapping("/{communityId}")
	CommunityView community(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String communityId) {
		CurrentUser user = resolveSafely(sessionToken);
		Community c = communityService.getById(communityId);
		return view(c, user != null && communityService.isMember(c.id(), user.id().value()));
	}

	private CommunityView view(Community c, boolean joined) {
		return view(c, communityService.countMembers(c.id()), joined);
	}

	private CommunityView view(Community c, int members, boolean joined) {
		return new CommunityView(c.id(), c.name(), c.description(), members, c.topic(), c.cover(), c.avatar(),
			c.bannerHtml(), joined, c.place() == null ? null : c.place().city(),
			c.place() == null ? null : c.place().state());
	}

	private CurrentUser resolveSafely(String sessionToken) {
		try {
			return currentUserResolver.resolve(sessionToken);
		} catch (Exception e) {
			return null;
		}
	}

	@PostMapping
	ResponseEntity<String> create(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CommunityRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/communities", idempotencyKey, hash,
			() -> {
				Community c = communityService.create(request.name(), request.description(), request.topic(),
					request.cover(), request.avatar(), request.city(), request.state(), user.id().value());
				return new CommandResponse(201, view(c, 1, true));
			});
	}

	@PatchMapping("/{communityId}")
	ResponseEntity<String> update(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId, @RequestBody CommunityRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("communityId", communityId, "request", request));
		return idempotency.execute(user.id(), "PATCH", "/api/communities/{communityId}", idempotencyKey, hash,
			() -> {
				Community c = communityService.update(communityId, request.name(), request.description(),
					request.topic(), request.cover(), request.avatar(), request.bannerHtml(), request.city(),
					request.state(), user.id().value());
				return new CommandResponse(200, view(c, communityService.isMember(c.id(), user.id().value())));
			});
	}

	@PostMapping("/{communityId}/members")
	ResponseEntity<String> join(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("communityId", communityId));
		return idempotency.execute(user.id(), "POST", "/api/communities/{communityId}/members", idempotencyKey, hash,
			() -> {
				communityService.join(communityId, user.id().value());
				return new CommandResponse(200, new StatusResponse(communityId, "JOINED"));
			});
	}

	@DeleteMapping("/{communityId}/members")
	ResponseEntity<String> leave(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("communityId", communityId));
		return idempotency.execute(user.id(), "DELETE", "/api/communities/{communityId}/members", idempotencyKey, hash,
			() -> {
				communityService.leave(communityId, user.id().value());
				return new CommandResponse(200, new StatusResponse(communityId, "LEFT"));
			});
	}

	/** Comunidades favoritadas pelo usuario (bookmark), para o menu lateral e a estrela dos cards. */
	@GetMapping("/favorites")
	PageResponse<CommunityView> favorites(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "50") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		var items = communityService.listFavorites(user.id().value(), limit).stream()
			.map(c -> view(c, communityService.isMember(c.id(), user.id().value())))
			.toList();
		return new PageResponse<>(items, null);
	}

	@PostMapping("/{communityId}/favorite")
	ResponseEntity<String> favorite(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("communityId", communityId, "op", "favorite"));
		return idempotency.execute(user.id(), "POST", "/api/communities/{communityId}/favorite", idempotencyKey, hash,
			() -> {
				communityService.favorite(communityId, user.id().value());
				return new CommandResponse(200, new StatusResponse(communityId, "FAVORITED"));
			});
	}

	@DeleteMapping("/{communityId}/favorite")
	ResponseEntity<String> unfavorite(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("communityId", communityId, "op", "unfavorite"));
		return idempotency.execute(user.id(), "DELETE", "/api/communities/{communityId}/favorite", idempotencyKey, hash,
			() -> {
				communityService.unfavorite(communityId, user.id().value());
				return new CommandResponse(200, new StatusResponse(communityId, "UNFAVORITED"));
			});
	}

	/**
	 * Comunidades perto de quem pede, pela cidade/UF do PROPRIO perfil: mesma cidade primeiro,
	 * depois o resto do estado, por numero de membros. Perfil sem cidade = lista vazia.
	 */
	@GetMapping("/local")
	PageResponse<CommunityView> local(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		var place = userProfileRepository.findById(user.id().value()).map(UserProfile::place).orElse(null);
		var items = communityService.listLocal(place, Math.min(Math.max(limit, 1), 50)).stream()
			.map(c -> view(c, communityService.isMember(c.id(), user.id().value())))
			.toList();
		return new PageResponse<>(items, null);
	}

	/** Comunidades do usuario (menu lateral). Exige sessao: e a lista pessoal, nao o catalogo publico. */
	@GetMapping("/mine")
	PageResponse<CommunityView> mine(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		var items = communityService.listMine(user.id().value(), limit).stream()
			.map(c -> view(c, true))
			.toList();
		return new PageResponse<>(items, null);
	}

	/** Transfere a administracao para outro membro (so o dono atual). */
	@PostMapping("/{communityId}/transfer")
	ResponseEntity<String> transfer(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId, @RequestBody TransferOwnershipRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("communityId", communityId, "newOwnerId",
			request.newOwnerId() == null ? "" : request.newOwnerId()));
		return idempotency.execute(user.id(), "POST", "/api/communities/{communityId}/transfer", idempotencyKey, hash,
			() -> {
				Community c = communityService.transferOwnership(communityId, user.id().value(), request.newOwnerId());
				return new CommandResponse(200, view(c, communityService.isMember(c.id(), user.id().value())));
			});
	}

	/** Exclui a comunidade (so o dono). */
	@DeleteMapping("/{communityId}")
	ResponseEntity<String> delete(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("communityId", communityId));
		return idempotency.execute(user.id(), "DELETE", "/api/communities/{communityId}", idempotencyKey, hash,
			() -> {
				communityService.deleteCommunity(communityId, user.id().value(), adminGuard.isAdmin(user));
				return new CommandResponse(200, new StatusResponse(communityId, "DELETED"));
			});
	}

	@GetMapping("/{communityId}/members")
	PageResponse<MemberView> members(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String communityId) {
		// Anonymous viewers get what the page renders (name, @username, avatar, admin badge); the
		// internal user id is only for signed-in viewers (admin checks, ownership transfer).
		boolean signedIn = resolveSafely(sessionToken) != null;
		List<CommunityMember> members = communityService.findMembers(communityId);
		Set<String> userIds = members.stream().map(CommunityMember::userId).collect(Collectors.toSet());
		Map<String, UserProfile> profiles = userProfileRepository.findAllByIds(userIds).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		var items = members.stream()
			.map(m -> {
				UserProfile p = profiles.get(m.userId());
				return new MemberView(signedIn ? m.userId() : null,
					p != null ? p.username() : null,
					p != null ? p.displayWithNickname() : null,
					p != null ? p.avatar() : null,
					m.role().name(),
					NameStyleView.from(p));
			})
			.toList();
		return new PageResponse<>(items, null);
	}

	/** Canais privados: qualquer membro lê e cria; quem não participa recebe 403. */
	@GetMapping("/{communityId}/channels")
	List<CommunityChannelService.ChannelView> channels(@PathVariable String communityId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return channelService.list(communityId, user.id().value());
	}

	@PostMapping("/{communityId}/channels")
	CommunityChannelService.ChannelView createChannel(@PathVariable String communityId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody CreateChannelRequest body) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return channelService.create(communityId, user.id().value(), body.name(), body.topic(), body.description());
	}

	@GetMapping("/{communityId}/channels/{channelId}/posts")
	FeedPage channelFeed(@PathVariable String communityId, @PathVariable String channelId,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Instant before = cursorCodec.decode(cursor).orElse(null);
		return getFeedService.getChannelFeed(communityId, channelId, before, limit, user.id());
	}

	record CreateChannelRequest(String name, String topic, String description) {
	}

	@GetMapping("/{communityId}/feed")
	FeedPage communityFeed(@PathVariable String communityId,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = resolveSafely(sessionToken);
		Instant before = cursorCodec.decode(cursor).orElse(null);
		return getFeedService.getCommunityFeed(communityId, before, limit,
			user != null ? user.id() : new com.socialcoisa.rede.feed.domain.UserId("anonymous"));
	}

	/** O post fixado no topo (um só). Visitante pode ler: o feed da comunidade é público. */
	@GetMapping("/{communityId}/pin")
	Object pinned(@PathVariable String communityId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = resolveSafely(sessionToken);
		String postId = communityService.pinnedPostId(communityId)
			.orElseThrow(() -> new com.socialcoisa.rede.shared.web.ApiException(404, "NO_PINNED_POST",
				"Nao ha publicacao fixada nesta comunidade."));
		return getFeedService.getPost(postId, user != null ? user.id() : null);
	}

	@PostMapping("/{communityId}/pin")
	ResponseEntity<String> pin(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId, @RequestBody PinRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (request == null || request.postId() == null || request.postId().isBlank()) {
			throw new com.socialcoisa.rede.shared.web.ApiException(400, "BAD_REQUEST", "Informe o post.");
		}
		String hash = idempotency.hash(Map.of("communityId", communityId, "postId", request.postId()));
		return idempotency.execute(user.id(), "POST", "/api/communities/{communityId}/pin", idempotencyKey, hash,
			() -> {
				String postId = communityService.pinPost(communityId, request.postId().trim(), user.id().value());
				return new CommandResponse(200, getFeedService.getPost(postId, user.id()));
			});
	}

	@DeleteMapping("/{communityId}/pin")
	ResponseEntity<String> unpin(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("communityId", communityId, "action", "unpin"));
		return idempotency.execute(user.id(), "DELETE", "/api/communities/{communityId}/pin", idempotencyKey, hash,
			() -> {
				communityService.unpinPost(communityId, user.id().value());
				return new CommandResponse(200, new StatusResponse(communityId, "UNPINNED"));
			});
	}

	record PinRequest(String postId) {
	}

	record CommunityView(String id, String name, String description, int members, String topic, String cover,
			String avatar, String bannerHtml, boolean joined, String city, String state) {
	}

	record CommunityRequest(String name, String description, String topic, String cover, String avatar,
			String bannerHtml, String city, String state) {
	}

	record TransferOwnershipRequest(String newOwnerId) {
	}

	record MemberView(String userId, String username, String name, String avatar, String role,
			NameStyleView nameStyle) {
	}

}
