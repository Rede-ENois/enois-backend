package com.socialcoisa.rede.presence.adapters.in.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

import com.socialcoisa.rede.presence.application.PresenceService;
import com.socialcoisa.rede.presence.domain.PresenceStatus;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

@RestController
class PresenceController {
	private final PresenceService service;
	private final UserProfileRepository profiles;
	private final SanctionGateReader sanctionGate;
	private final CurrentUserResolver currentUserResolver;

	PresenceController(PresenceService service, UserProfileRepository profiles, SanctionGateReader sanctionGate,
			CurrentUserResolver currentUserResolver) {
		this.service = service;
		this.profiles = profiles;
		this.sanctionGate = sanctionGate;
		this.currentUserResolver = currentUserResolver;
	}

	record PresenceView(String status) {
	}

	record StatusRequest(PresenceStatus status) {
	}

	@GetMapping("/me/presence")
	PresenceView own(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return new PresenceView(service.ownStatus(userId).name());
	}

	@PutMapping("/me/presence")
	PresenceView update(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody StatusRequest request) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		if (request == null || request.status() == null) {
			throw new ApiException(400, "INVALID_PRESENCE_STATUS", "Escolha um estado de presenca.");
		}
		return new PresenceView(service.setStatus(userId, request.status()).name());
	}

	@PostMapping("/me/presence/heartbeat")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void heartbeat(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		service.heartbeat(userId);
	}

	@GetMapping("/users/{username}/presence")
	PresenceView publicPresence(@PathVariable String username) {
		var profile = profiles.findByUsername(username)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		if (sanctionGate.banned(profile.id())) {
			throw new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado.");
		}
		return new PresenceView(service.publicStatus(profile.id()));
	}
}
