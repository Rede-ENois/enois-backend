package com.socialcoisa.rede.notifications.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.corporate.application.CorporateProfileService;
import com.socialcoisa.rede.corporate.domain.CorporateProfile;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.notifications.domain.Notification;
import com.socialcoisa.rede.notifications.domain.NotificationContext;
import com.socialcoisa.rede.profile.application.NameStyleView;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/notifications")
class NotificationContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final NotificationService notificationService;
	private final CursorCodec cursorCodec;
	private final UserProfileRepository userProfileRepository;
	private final BlockedUsersReader blockedUsersReader;
	private final CorporateProfileService corporateProfiles;

	NotificationContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			NotificationService notificationService, CursorCodec cursorCodec,
			UserProfileRepository userProfileRepository, BlockedUsersReader blockedUsersReader,
			CorporateProfileService corporateProfiles) {
		this.corporateProfiles = corporateProfiles;
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.notificationService = notificationService;
		this.cursorCodec = cursorCodec;
		this.userProfileRepository = userProfileRepository;
		this.blockedUsersReader = blockedUsersReader;
	}

	@GetMapping
	PageResponse<NotificationView> notifications(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
			@RequestParam(required = false) String context) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Instant before = cursorCodec.decode(cursor).orElse(null);
		// Bloqueio: notificacao de quem foi bloqueado (ou de quem bloqueou) nao aparece mais.
		Set<String> hidden = blockedUsersReader.hiddenIdsFor(user.id().value());
		// O cursor sai da pagina LIDA, antes do filtro de bloqueio: senao a pagina que perde itens
		// para o filtro parecia a ultima e as notificacoes seguintes ficavam inalcancaveis.
		List<Notification> page = notificationService.list(user.id().value(), before, parseContext(context), limit);
		List<Notification> notifications = page.stream()
			.filter(n -> n.actorId() == null || !hidden.contains(n.actorId()))
			.toList();
		Map<String, UserProfile> profiles = resolveActors(notifications);
		Map<String, CorporateProfile> professionals = professionalActors(notifications);
		var items = notifications.stream()
			.map(n -> {
				NotificationContext notificationContext = NotificationContext.of(n.kind());
				// Aviso de trabalho mostra QUEM agiu pela identidade profissional (recrutador, contratante).
				CorporateProfile pro = notificationContext == NotificationContext.PROFESSIONAL
					? professionals.get(n.actorId()) : null;
				UserProfile p = profiles.get(n.actorId());
				return new NotificationView(n.id(), n.kind(), notificationContext.name(), n.actorId(),
					pro != null ? pro.name() : p != null ? p.name() : null,
					pro != null ? pro.username() : p != null ? p.username() : null,
					pro != null ? (pro.avatar() == null ? "" : pro.avatar()) : p != null ? p.avatar() : null,
					pro != null ? "CORPORATE" : "SOCIAL",
					pro == null && p != null ? NameStyleView.from(p) : null,
					n.title(), n.body(),
					n.createdAt() != null ? n.createdAt().toString() : null,
					n.isRead(), n.href(), n.ctaLabel(), n.actorCount());
			})
			.toList();
		String nextCursor = page.size() == limit
			? cursorCodec.encode(page.get(page.size() - 1).createdAt()) : null;
		return new PageResponse<>(items, nextCursor);
	}

	private Map<String, CorporateProfile> professionalActors(List<Notification> notifications) {
		Set<String> actorIds = notifications.stream()
			.filter(n -> NotificationContext.of(n.kind()) == NotificationContext.PROFESSIONAL)
			.map(Notification::actorId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		return corporateProfiles.activeByUserIds(actorIds);
	}

	/** Blank keeps every context (older clients); otherwise SOCIAL or PROFESSIONAL. */
	private static NotificationContext parseContext(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
		if (!"SOCIAL".equals(normalized) && !"PROFESSIONAL".equals(normalized)) {
			throw new ApiException(400, "INVALID_CONTEXT", "Contexto invalido: use SOCIAL ou PROFESSIONAL.");
		}
		return NotificationContext.valueOf(normalized);
	}

	private Map<String, UserProfile> resolveActors(List<Notification> notifications) {
		Set<String> actorIds = notifications.stream()
			.map(Notification::actorId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		if (actorIds.isEmpty()) return Map.of();
		return userProfileRepository.findAllByIds(actorIds).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
	}

	record NotificationView(String id, String kind, String context, String actorId, String actorName,
			String actorUsername, String actorAvatar, String actorIdentity, NameStyleView actorStyle, String title,
			String body, String createdAt, boolean read, String href, String ctaLabel, int actorCount) {
	}

	@PostMapping("/{notificationId}/read")
	ResponseEntity<String> read(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String notificationId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("notificationId", notificationId));
		return idempotency.execute(user.id(), "POST", "/api/notifications/{notificationId}/read", idempotencyKey, hash,
			() -> {
				notificationService.markRead(notificationId, user.id().value());
				return new CommandResponse(200, new StatusResponse(notificationId, "READ"));
			});
	}

	@PostMapping("/read-all")
	ResponseEntity<String> readAll(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("userId", user.id().value()));
		return idempotency.execute(user.id(), "POST", "/api/notifications/read-all", idempotencyKey, hash,
			() -> {
				notificationService.markAllRead(user.id().value());
				return new CommandResponse(200, new StatusResponse(user.id().value(), "ALL_READ"));
			});
	}

}
