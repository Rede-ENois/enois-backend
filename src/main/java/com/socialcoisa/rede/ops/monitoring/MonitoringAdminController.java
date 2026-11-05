package com.socialcoisa.rede.ops.monitoring;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.ModeratorGuard;

/**
 * Painel de monitorabilidade (somente admin). O gate de origem também restringe /admin/** à origem
 * confiável do frontend.
 */
@RestController
@RequestMapping("/admin/monitoring")
class MonitoringAdminController {

	private final MonitoringService monitoring;
	private final UserMetricsService userMetrics;
	private final CurrentUserResolver currentUserResolver;
	private final ModeratorGuard moderatorGuard;

	MonitoringAdminController(MonitoringService monitoring, UserMetricsService userMetrics,
			CurrentUserResolver currentUserResolver, ModeratorGuard moderatorGuard) {
		this.monitoring = monitoring;
		this.userMetrics = userMetrics;
		this.currentUserResolver = currentUserResolver;
		this.moderatorGuard = moderatorGuard;
	}

	@GetMapping("/overview")
	MonitoringService.OverviewView overview(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		moderatorGuard.requireModerator(currentUserResolver.resolve(sessionToken));
		return monitoring.overview();
	}

	/** Contas da comunidade: total, entradas por dia, crescimento e projeção. */
	@GetMapping("/users")
	UserMetricsService.UsersView users(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		moderatorGuard.requireModerator(currentUserResolver.resolve(sessionToken));
		return userMetrics.users();
	}

	@GetMapping("/events")
	List<MonitoringService.EventView> events(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String level,
			@RequestParam(required = false) String type,
			@RequestParam(required = false, name = "q") String query,
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) String before,
			@RequestParam(defaultValue = "100") int limit) {
		moderatorGuard.requireModerator(currentUserResolver.resolve(sessionToken));
		return monitoring.events(level, type, query, from, to, before, limit);
	}
}
