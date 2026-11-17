package com.socialcoisa.rede.ops.monitoring;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.moderation.application.AdminAuthorityAudit;
import com.socialcoisa.rede.shared.web.AdminDenialCounter;
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
	private final RoleplayMetricsService roleplayMetrics;
	private final CurrentUserResolver currentUserResolver;
	private final ModeratorGuard moderatorGuard;
	private final AdminAuthorityAudit denied;
	private final AdminDenialCounter deniedBaseline;

	MonitoringAdminController(MonitoringService monitoring, UserMetricsService userMetrics,
			RoleplayMetricsService roleplayMetrics, CurrentUserResolver currentUserResolver,
			ModeratorGuard moderatorGuard, AdminAuthorityAudit denied, AdminDenialCounter deniedBaseline) {
		this.monitoring = monitoring;
		this.userMetrics = userMetrics;
		this.roleplayMetrics = roleplayMetrics;
		this.currentUserResolver = currentUserResolver;
		this.moderatorGuard = moderatorGuard;
		this.denied = denied;
		this.deniedBaseline = deniedBaseline;
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

	/**
	 * Recusas de autorização em rota administrativa: o total, quantos avisos de repetição já saíram,
	 * as contas que passaram do limiar na janela (com a última tentativa) e quantas varreduras sem
	 * sessão passaram do limiar. Em memória por pod; a trilha durável está em audit_logs com a ação
	 * ADMIN_AUTHORITY_DENIED.
	 */
	@GetMapping("/denied-admin")
	DeniedAdminView deniedAdmin(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		moderatorGuard.requireModerator(currentUserResolver.resolve(sessionToken));
		return new DeniedAdminView(denied.summary(), denied.suspects().stream()
			.map(suspect -> new SuspectView(suspect.userId(), suspect.denialsInWindow(),
				suspect.totalDenials(), suspect.lastDenial() == null ? null : new LastDenialView(
					suspect.lastDenial().method(), suspect.lastDenial().route(), suspect.lastDenial().reason())))
			.toList(), deniedBaseline.aboveThreshold());
	}

	record DeniedAdminView(AdminAuthorityAudit.Summary summary, List<SuspectView> suspects,
			int anonymousScans) {
	}

	record SuspectView(String userId, int denialsInWindow, long totalDenials, LastDenialView lastDenial) {
	}

	record LastDenialView(String method, String route, String reason) {
	}

	/** Métricas do Éroleplay: campanhas por estado/visibilidade/sistema, volume de dados e ritmo. */
	@GetMapping("/roleplay")
	RoleplayMetricsService.RoleplayView roleplay(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		moderatorGuard.requireModerator(currentUserResolver.resolve(sessionToken));
		return roleplayMetrics.roleplay();
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
