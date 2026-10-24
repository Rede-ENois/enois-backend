package com.socialcoisa.rede.ops.monitoring;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.moderation.application.ModeratorService;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Painel de monitorabilidade (somente admin). O gate de origem também restringe /admin/** à origem
 * confiável do frontend.
 */
@RestController
@RequestMapping("/admin/monitoring")
class MonitoringAdminController {

	private final MonitoringService monitoring;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;
	private final ModeratorService moderators;

	MonitoringAdminController(MonitoringService monitoring, CurrentUserResolver currentUserResolver,
			AdminGuard adminGuard, ModeratorService moderators) {
		this.monitoring = monitoring;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
		this.moderators = moderators;
	}

	@GetMapping("/overview")
	MonitoringService.OverviewView overview(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		requireModerator(currentUserResolver.resolve(userId, sessionToken));
		return monitoring.overview();
	}

	@GetMapping("/events")
	List<MonitoringService.EventView> events(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String level,
			@RequestParam(required = false) String type,
			@RequestParam(required = false, name = "q") String query,
			@RequestParam(required = false) String before,
			@RequestParam(defaultValue = "100") int limit) {
		requireModerator(currentUserResolver.resolve(userId, sessionToken));
		return monitoring.events(level, type, query, before, limit);
	}

	private void requireModerator(CurrentUser user) {
		if (!adminGuard.isAdmin(user) && !moderators.isModerator(user.id().value())) {
			throw new ApiException(403, "FORBIDDEN", "Acesso restrito a moderadores.");
		}
	}
}
