package com.socialcoisa.rede.jobs.adapters.in.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.jobs.application.RecruitmentHubService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Central de recrutamento: as vagas de quem publica, com o funil de cada uma.
 *
 * <p>A lista é sempre a de quem está na sessão — não existe parâmetro de "recrutador" vindo
 * do cliente, então uma empresa não alcança o processo de outra por aqui.
 */
@RestController
@RequestMapping("/jobs/recruitment")
class RecruitmentHubController {

	private final RecruitmentHubService hub;
	private final CurrentUserResolver currentUserResolver;

	RecruitmentHubController(RecruitmentHubService hub, CurrentUserResolver currentUserResolver) {
		this.hub = hub;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping
	RecruitmentHubService.HubView hub(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "50") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return hub.hubOf(user.id().value(), limit);
	}

}