package com.socialcoisa.rede.jobs.adapters.in.web;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.jobs.application.RecruitmentHubService;
import com.socialcoisa.rede.jobs.application.SelectionProcessService;
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
	private final SelectionProcessService process;
	private final CurrentUserResolver currentUserResolver;

	RecruitmentHubController(RecruitmentHubService hub, SelectionProcessService process,
			CurrentUserResolver currentUserResolver) {
		this.hub = hub;
		this.process = process;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping
	RecruitmentHubService.HubView hub(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "50") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return hub.hubOf(user.id().value(), limit);
	}


	// ---------- Biblioteca de modelos de processo seletivo ----------

	record CreateTemplateRequest(String name, java.util.List<String> stages) {
	}

	record TemplateView(String id, String name, java.util.List<String> stages, String createdAt,
			String updatedAt) {

		static TemplateView of(com.socialcoisa.rede.jobs.domain.SelectionProcessTemplate template) {
			return new TemplateView(template.id(), template.name(), template.stageNames(),
				template.createdAt().toString(), template.updatedAt().toString());
		}
	}

	/** A biblioteca é de quem está na sessão — não existe "recrutador" vindo do cliente. */
	@GetMapping("/templates")
	java.util.List<TemplateView> templates(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "30") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return process.templatesOf(user.id().value(), limit).stream().map(TemplateView::of).toList();
	}

	@PostMapping("/templates")
	TemplateView createTemplate(@RequestBody CreateTemplateRequest body,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return TemplateView.of(process.createTemplate(user.id().value(), body.name(), body.stages()));
	}

	@DeleteMapping("/templates/{templateId}")
	void deleteTemplate(@PathVariable String templateId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		process.deleteTemplate(templateId, user.id().value());
	}

}
