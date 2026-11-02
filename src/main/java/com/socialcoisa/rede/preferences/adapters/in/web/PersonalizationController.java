package com.socialcoisa.rede.preferences.adapters.in.web;

import java.time.LocalDate;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.preferences.application.PersonalizationService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Os três controles de personalização — uma única fonte de verdade no backend:
 *
 * GET /me/preferences — lê o estado atual (defaults: listas vazias).
 * PUT /me/preferences — atualiza parcialmente (nulo/vazio = volta ao padrão).
 *
 * O backend valida cada valor contra a taxonomia/módulos reais e é a fonte aplicada
 * consistentemente em menu, notificações, cards, recomendações e descoberta.
 */
@RestController
class PersonalizationController {

	private final PersonalizationService service;
	private final CurrentUserResolver currentUserResolver;

	PersonalizationController(PersonalizationService service, CurrentUserResolver currentUserResolver) {
		this.service = service;
		this.currentUserResolver = currentUserResolver;
	}

	record PersonalizationResponse(List<String> likedTopics, List<String> blockedTopics,
			List<String> disabledModules, boolean nightRestReminder, LocalDate nightRestDismissedOn,
			boolean liteMode, boolean batataMode) {
	}

	record DismissNightRestRequest(String night) {
	}

	record UpdateRequest(List<String> likedTopics, List<String> blockedTopics, List<String> disabledModules,
			Boolean nightRestReminder, Boolean liteMode, Boolean batataMode) {
	}

	@GetMapping("/me/preferences")
	PersonalizationResponse me(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		var personalization = service.of(user.id().value());
		return view(personalization);
	}

	@PutMapping("/me/preferences")
	PersonalizationResponse update(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody UpdateRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		var updated = service.update(user.id().value(),
			request == null ? null : request.likedTopics(),
			request == null ? null : request.blockedTopics(),
			request == null ? null : request.disabledModules(),
			request == null ? null : request.nightRestReminder(),
			request == null ? null : request.liteMode(),
			request == null ? null : request.batataMode());
		return view(updated);
	}

	/**
	 * Fecha o lembrete da madrugada para a noite informada (data local do aparelho). Persistir
	 * no banco é o que impede o popup de voltar a cada reload; a data diferente na madrugada
	 * seguinte libera o lembrete de novo às 00:00.
	 */
	@org.springframework.web.bind.annotation.PostMapping("/me/preferences/night-rest/dismissed")
	PersonalizationResponse dismissNightRest(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody DismissNightRestRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return view(service.dismissNightRest(user.id().value(), request == null ? null : request.night()));
	}

	private PersonalizationResponse view(PersonalizationService.Personalization personalization) {
		return new PersonalizationResponse(personalization.likedTopics(), personalization.blockedTopics(),
			personalization.disabledModules(), personalization.nightRestReminder(),
			personalization.nightRestDismissedOn(), personalization.liteMode(),
			personalization.batataMode());
	}
}
