package com.socialcoisa.rede.eroleplay.adapters.in.web;

import java.time.Instant;
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

import com.socialcoisa.rede.eroleplay.application.CampaignService;
import com.socialcoisa.rede.eroleplay.domain.Campaign;
import com.socialcoisa.rede.eroleplay.domain.CampaignJoinRequest;
import com.socialcoisa.rede.eroleplay.domain.CampaignMember;
import com.socialcoisa.rede.eroleplay.domain.CampaignMembership;
import com.socialcoisa.rede.eroleplay.domain.CampaignRole;
import com.socialcoisa.rede.eroleplay.domain.CampaignVisibility;
import com.socialcoisa.rede.eroleplay.domain.GameSystemFamily;
import com.socialcoisa.rede.eroleplay.domain.GameTable;
import com.socialcoisa.rede.eroleplay.domain.JoinOutcome;
import com.socialcoisa.rede.eroleplay.domain.TableSeat;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.profile.application.NameStyleView;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

/**
 * Endpoints do Éroleplay.
 *
 * <p>A leitura pública devolve APENAS a apresentação da campanha (nome, descrição, ambientação,
 * sistema de regras) — nunca participantes, mesas nem conteúdo interno. Quem não participa vê
 * {@code joined=false} e {@code viewerRole=null}, e nada além disso, mesmo sabendo o id.</p>
 */
@RestController
@RequestMapping("/roleplay/campaigns")
class CampaignController {

	private final CampaignService campaignService;
	private final CurrentUserResolver currentUserResolver;
	private final UserProfileRepository userProfileRepository;
	private final IdempotencyApplicationService idempotency;
	private final CursorCodec cursorCodec;

	CampaignController(CampaignService campaignService, CurrentUserResolver currentUserResolver,
			UserProfileRepository userProfileRepository, IdempotencyApplicationService idempotency,
			CursorCodec cursorCodec) {
		this.campaignService = campaignService;
		this.currentUserResolver = currentUserResolver;
		this.userProfileRepository = userProfileRepository;
		this.idempotency = idempotency;
		this.cursorCodec = cursorCodec;
	}

	@GetMapping
	PageResponse<CampaignView> campaigns(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String query, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit) {
		CurrentUser viewer = resolveSafely(sessionToken);
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<Campaign> campaigns = campaignService.list(query, before, limit);
		var items = campaigns.stream().map(campaign -> view(campaign, viewer)).toList();
		String nextCursor = campaigns.size() == limit
			? cursorCodec.encode(campaigns.get(campaigns.size() - 1).createdAt())
			: null;
		return new PageResponse<>(items, nextCursor);
	}

	@GetMapping("/mine")
	PageResponse<CampaignView> mine(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<CampaignMembership> memberships = campaignService.listMine(user.id().value(), limit);
		var items = memberships.stream()
			.map(membership -> view(membership.campaign(), user))
			.toList();
		return new PageResponse<>(items, null);
	}

	@GetMapping("/{slugOrId}")
	CampaignView campaign(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String slugOrId) {
		CurrentUser viewer = resolveSafely(sessionToken);
		return view(campaignService.resolve(slugOrId), viewer);
	}

	@PostMapping
	ResponseEntity<String> create(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CampaignRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/roleplay/campaigns", idempotencyKey, hash, () -> {
			Campaign campaign = campaignService.create(request.name(), request.description(), request.setting(),
				request.gameSystem(), familyOf(request), visibilityOf(request), user.id().value());
			return new CommandResponse(201, view(campaign, user));
		});
	}

	@PatchMapping("/{campaignId}")
	ResponseEntity<String> update(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @RequestBody CampaignRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "request", request));
		return idempotency.execute(user.id(), "PATCH", "/api/roleplay/campaigns/{campaignId}", idempotencyKey, hash,
			() -> {
				Campaign campaign = campaignService.update(campaignId, request.name(), request.description(),
					request.setting(), request.gameSystem(), familyOf(request), visibilityOf(request),
					user.id().value());
				return new CommandResponse(200, view(campaign, user));
			});
	}

	@PostMapping("/{campaignId}/join")
	ResponseEntity<String> join(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "join"));
		return idempotency.execute(user.id(), "POST", "/api/roleplay/campaigns/{campaignId}/join", idempotencyKey,
			hash, () -> {
				JoinOutcome outcome = campaignService.join(campaignId, user.id().value());
				return new CommandResponse(200, new JoinResponse(outcome.name()));
			});
	}

	@PostMapping("/{campaignId}/invite")
	ResponseEntity<String> invite(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @RequestBody InviteRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "request", request));
		return idempotency.execute(user.id(), "POST", "/api/roleplay/campaigns/{campaignId}/invite", idempotencyKey,
			hash, () -> {
				campaignService.invite(campaignId, user.id().value(), request.userId());
				return new CommandResponse(200, new StatusResponse(request.userId(), "INVITED"));
			});
	}

	@DeleteMapping("/{campaignId}/members/me")
	ResponseEntity<String> leave(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "leave"));
		return idempotency.execute(user.id(), "DELETE", "/api/roleplay/campaigns/{campaignId}/members/me",
			idempotencyKey, hash, () -> {
				campaignService.leave(campaignId, user.id().value());
				return new CommandResponse(200, new StatusResponse(campaignId, "LEFT"));
			});
	}

	@GetMapping("/{campaignId}/members")
	PageResponse<MemberView> members(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<CampaignMember> members = campaignService.listMembers(campaignId, user.id().value());
		Map<String, UserProfile> profiles = profilesOf(members.stream().map(CampaignMember::userId).toList());
		var items = members.stream().map(member -> memberView(member, profiles.get(member.userId()))).toList();
		return new PageResponse<>(items, null);
	}

	@DeleteMapping("/{campaignId}/members/{memberId}")
	ResponseEntity<String> removeMember(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String memberId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "memberId", memberId));
		return idempotency.execute(user.id(), "DELETE", "/api/roleplay/campaigns/{campaignId}/members/{memberId}",
			idempotencyKey, hash, () -> {
				campaignService.removeMember(campaignId, user.id().value(), memberId);
				return new CommandResponse(200, new StatusResponse(memberId, "REMOVED"));
			});
	}

	@PostMapping("/{campaignId}/members/{memberId}/transfer-master")
	ResponseEntity<String> transferMaster(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String memberId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "memberId", memberId, "action",
			"transfer-master"));
		return idempotency.execute(user.id(), "POST",
			"/api/roleplay/campaigns/{campaignId}/members/{memberId}/transfer-master", idempotencyKey, hash, () -> {
				campaignService.transferMaster(campaignId, user.id().value(), memberId);
				return new CommandResponse(200, new StatusResponse(memberId, "MASTER"));
			});
	}

	@GetMapping("/{campaignId}/requests")
	PageResponse<RequestView> requests(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<CampaignJoinRequest> requests = campaignService.listPendingRequests(campaignId, user.id().value());
		Map<String, UserProfile> profiles = profilesOf(
			requests.stream().map(CampaignJoinRequest::userId).toList());
		var items = requests.stream().map(request -> requestView(request, profiles.get(request.userId()))).toList();
		return new PageResponse<>(items, null);
	}

	@PostMapping("/{campaignId}/requests/{requestId}/approve")
	ResponseEntity<String> approve(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String requestId) {
		return decide(sessionToken, idempotencyKey, campaignId, requestId, true);
	}

	@PostMapping("/{campaignId}/requests/{requestId}/reject")
	ResponseEntity<String> reject(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String requestId) {
		return decide(sessionToken, idempotencyKey, campaignId, requestId, false);
	}

	@PostMapping("/{campaignId}/archive")
	ResponseEntity<String> archive(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "archive"));
		return idempotency.execute(user.id(), "POST", "/api/roleplay/campaigns/{campaignId}/archive", idempotencyKey,
			hash, () -> {
				Campaign campaign = campaignService.archive(campaignId, user.id().value());
				return new CommandResponse(200, view(campaign, user));
			});
	}

	@GetMapping("/{campaignId}/tables")
	PageResponse<TableView> tables(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<GameTable> tables = campaignService.listTables(campaignId, user.id().value());
		var items = tables.stream().map(this::tableView).toList();
		return new PageResponse<>(items, null);
	}

	@PostMapping("/{campaignId}/tables")
	ResponseEntity<String> createTable(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @RequestBody TableRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "request", request));
		return idempotency.execute(user.id(), "POST", "/api/roleplay/campaigns/{campaignId}/tables", idempotencyKey,
			hash, () -> {
				GameTable table = campaignService.createTable(campaignId, request.name(), user.id().value(),
					request.open() == null || request.open());
				return new CommandResponse(201, tableView(table));
			});
	}

	@GetMapping("/{campaignId}/tables/{tableId}/seats")
	PageResponse<SeatView> seats(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId, @PathVariable String tableId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<TableSeat> seats = campaignService.listSeats(tableId, user.id().value());
		Map<String, UserProfile> profiles = profilesOf(seats.stream().map(TableSeat::userId).toList());
		var items = seats.stream().map(seat -> seatView(seat, profiles.get(seat.userId()))).toList();
		return new PageResponse<>(items, null);
	}

	@PostMapping("/{campaignId}/tables/{tableId}/sit")
	ResponseEntity<String> sit(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String tableId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "tableId", tableId, "action", "sit"));
		return idempotency.execute(user.id(), "POST",
			"/api/roleplay/campaigns/{campaignId}/tables/{tableId}/sit", idempotencyKey, hash, () -> {
				campaignService.sit(tableId, user.id().value());
				return new CommandResponse(200, new StatusResponse(tableId, "SEATED"));
			});
	}

	@DeleteMapping("/{campaignId}/tables/{tableId}/sit")
	ResponseEntity<String> leaveTable(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String tableId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "tableId", tableId, "action", "leave-table"));
		return idempotency.execute(user.id(), "DELETE",
			"/api/roleplay/campaigns/{campaignId}/tables/{tableId}/sit", idempotencyKey, hash, () -> {
				campaignService.leaveTable(tableId, user.id().value());
				return new CommandResponse(200, new StatusResponse(tableId, "LEFT"));
			});
	}

	private ResponseEntity<String> decide(String sessionToken, String idempotencyKey, String campaignId,
			String requestId, boolean approve) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String action = approve ? "approve" : "reject";
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "requestId", requestId, "action", action));
		return idempotency.execute(user.id(), "POST",
			"/api/roleplay/campaigns/{campaignId}/requests/{requestId}/" + action, idempotencyKey, hash, () -> {
				CampaignJoinRequest request = campaignService.decideRequest(campaignId, requestId, user.id().value(),
					approve);
				return new CommandResponse(200, new StatusResponse(request.id(), request.status().name()));
			});
	}

	private CampaignView view(Campaign campaign, CurrentUser viewer) {
		CampaignRole role = viewer == null ? null
			: campaignService.roleOf(campaign.id(), viewer.id().value());
		return new CampaignView(campaign.id(), campaign.slug(), campaign.name(), campaign.description(),
			campaign.setting(), campaign.gameSystem(), campaign.systemFamily().name(), campaign.visibility().name(),
			campaign.status().name(), campaign.memberCount(), role == null ? null : role.name(), role != null,
			campaign.createdAt());
	}

	private TableView tableView(GameTable table) {
		return new TableView(table.id(), table.campaignId(), table.name(), table.ownerId(), table.voiceRoomId(),
			table.isOpen(), table.createdAt());
	}

	private MemberView memberView(CampaignMember member, UserProfile profile) {
		if (profile == null) {
			return new MemberView(member.userId(), null, null, null, member.role().name(), member.joinedAt(), null);
		}
		return new MemberView(member.userId(), profile.username(), profile.name(), profile.avatar(),
			member.role().name(), member.joinedAt(), NameStyleView.from(profile));
	}

	private SeatView seatView(TableSeat seat, UserProfile profile) {
		if (profile == null) {
			return new SeatView(seat.userId(), null, null, null, seat.seatedAt(), null);
		}
		return new SeatView(seat.userId(), profile.username(), profile.name(), profile.avatar(), seat.seatedAt(),
			NameStyleView.from(profile));
	}

	private RequestView requestView(CampaignJoinRequest request, UserProfile profile) {
		if (profile == null) {
			return new RequestView(request.id(), request.userId(), null, null, null, request.message(),
				request.createdAt(), null);
		}
		return new RequestView(request.id(), request.userId(), profile.username(), profile.name(), profile.avatar(),
			request.message(), request.createdAt(), NameStyleView.from(profile));
	}

	private Map<String, UserProfile> profilesOf(List<String> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		Map<String, UserProfile> byId = new java.util.HashMap<>();
		userProfileRepository.findAllByIds(userIds).forEach(profile -> byId.put(profile.id(), profile));
		return byId;
	}

	private CurrentUser resolveSafely(String sessionToken) {
		try {
			return currentUserResolver.resolve(sessionToken);
		}
		catch (ApiException e) {
			return null;
		}
	}

	/**
	 * A família do motor vem explícita quando o mestre escolhe uma família conhecida; senão
	 * deriva do identificador do sistema (o Registry resolve o adapter e ele diz a família).
	 * Um sistema desconhecido NÃO vira D20 em silêncio — vira erro, porque o mestre precisa saber
	 * que o motor não entende aquele sistema.
	 */
	private GameSystemFamily familyOf(CampaignRequest request) {
		if (request.systemFamily() != null && !request.systemFamily().isBlank()) {
			try {
				return GameSystemFamily.of(request.systemFamily());
			}
			catch (IllegalArgumentException error) {
				throw new ApiException(400, "INVALID_GAME_SYSTEM", error.getMessage());
			}
		}
		return null;
	}

	private CampaignVisibility visibilityOf(CampaignRequest request) {
		if (request.visibility() == null || request.visibility().isBlank()) {
			return null;
		}
		try {
			return CampaignVisibility.valueOf(request.visibility().trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException error) {
			throw new ApiException(400, "INVALID_VISIBILITY", "Visibilidade desconhecida.");
		}
	}

	record CampaignRequest(String name, String description, String setting, String gameSystem, String systemFamily,
			String visibility) {
	}

	record InviteRequest(String userId) {
	}

	record TableRequest(String name, Boolean open) {
	}

	record JoinResponse(String status) {
	}

	record CampaignView(String id, String slug, String name, String description, String setting, String gameSystem,
			String systemFamily, String visibility, String status, long members, String viewerRole, boolean joined,
			Instant createdAt) {
	}

	record MemberView(String userId, String username, String name, String avatar, String role, Instant joinedAt,
			NameStyleView nameStyle) {
	}

	record RequestView(String id, String userId, String username, String name, String avatar, String message,
			Instant createdAt, NameStyleView nameStyle) {
	}

	record TableView(String id, String campaignId, String name, String ownerId, String voiceRoomId, boolean open,
			Instant createdAt) {
	}

	record SeatView(String userId, String username, String name, String avatar, Instant seatedAt,
			NameStyleView nameStyle) {
	}
}
