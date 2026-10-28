package com.socialcoisa.rede.cosmetics.adapters.in.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.cosmetics.application.CosmeticEventService;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/** Os eventos grátis da loja: duelo Saci x Halloween e caça ao Saci. Leitura pública; escrita com sessão. */
@RestController
class CosmeticEventsController {

	private final CosmeticEventService events;
	private final CurrentUserResolver currentUserResolver;

	CosmeticEventsController(CosmeticEventService events, CurrentUserResolver currentUserResolver) {
		this.events = events;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping("/eventos/saci-halloween")
	CosmeticEventService.DuelView duel(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return events.duel(viewer(sessionToken));
	}

	@PostMapping("/eventos/saci-halloween/escolha")
	CosmeticEventService.DuelView choose(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody ChoiceRequest request) {
		return events.choose(currentUserResolver.resolve(sessionToken).id().value(), request.choice());
	}

	@GetMapping("/eventos/caca-saci")
	CosmeticEventService.HuntView hunt(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return events.hunt(viewer(sessionToken));
	}

	@PostMapping("/eventos/caca-saci/achados/{spot}")
	CosmeticEventService.HuntView find(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String spot) {
		return events.find(currentUserResolver.resolve(sessionToken).id().value(), spot);
	}

	private String viewer(String sessionToken) {
		return sessionToken == null ? null : currentUserResolver.resolve(sessionToken).id().value();
	}

	record ChoiceRequest(String choice) {
	}
}
