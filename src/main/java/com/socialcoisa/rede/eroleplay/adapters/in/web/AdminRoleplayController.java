package com.socialcoisa.rede.eroleplay.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.eroleplay.application.CampaignService;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Administração do Éroleplay. Hoje, a leitura da trilha de auditoria de uma campanha.
 *
 * <p>Estas rotas ficam sob {@code /admin}, portanto já passam por {@code AdminRouteInterceptor}
 * (trava central) e ainda chamam {@code AdminGuard} aqui, como defesa em profundidade. Campanha
 * desconhecida é 404, não histórico vazio — o mesmo contrato do painel de rede.</p>
 */
@RestController
@RequestMapping("/admin/roleplay/campaigns")
class AdminRoleplayController {

	private final CampaignService campaigns;
	private final AuditTrail audit;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminRoleplayController(CampaignService campaigns, AuditTrail audit, CurrentUserResolver currentUserResolver,
			AdminGuard adminGuard) {
		this.campaigns = campaigns;
		this.audit = audit;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	/** O histórico de atos da campanha, mais novo primeiro — o que o painel mostra. */
	@GetMapping("/{id}/history")
	List<AuditRecordView> history(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id, @RequestParam(defaultValue = "50") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		campaigns.requireExists(id); // Campanha desconhecida é 404, não histórico vazio.
		int capped = Math.min(Math.max(limit, 1), 200);
		return audit.recentForTarget(id, capped).stream()
			.map(record -> new AuditRecordView(record.id(), record.actorId(), record.action(), record.metadata(),
				record.createdAt()))
			.toList();
	}

	record AuditRecordView(String id, String actorId, String action, String metadata, Instant createdAt) {
	}
}
