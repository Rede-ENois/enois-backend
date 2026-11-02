package com.socialcoisa.rede.onboarding.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.onboarding.application.OnboardingService;
import com.socialcoisa.rede.onboarding.application.OnboardingService.CompleteCommand;
import com.socialcoisa.rede.onboarding.application.OnboardingService.CompleteResult;
import com.socialcoisa.rede.onboarding.application.OnboardingService.StarterPack;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * First-run onboarding. Whether it is still due comes in {@code onboardingCompleted} of
 * {@code /auth/me}; these routes serve the flow itself. Both writes are idempotent by design.
 */
@RestController
class OnboardingController {

	private final OnboardingService service;
	private final CurrentUserResolver currentUserResolver;

	OnboardingController(OnboardingService service, CurrentUserResolver currentUserResolver) {
		this.service = service;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping("/me/onboarding/starter-pack")
	StarterPack starterPack(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) List<String> topics, @RequestParam(required = false) List<String> skills) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return service.starterPack(userId, topics, skills);
	}

	@PostMapping("/me/onboarding/complete")
	CompleteResult complete(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) CompleteCommand request) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return service.complete(userId,
			request == null ? new CompleteCommand(null, null, null, null, null) : request);
	}

	@PostMapping("/me/onboarding/skip")
	CompleteResult skip(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return service.skip(currentUserResolver.resolve(sessionToken).id().value());
	}
}
