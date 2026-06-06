package com.socialcoisa.rede.profile.adapters.in.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.profile.application.UpdateProfileCommand;
import com.socialcoisa.rede.profile.application.port.in.GetMeUseCase;
import com.socialcoisa.rede.profile.application.port.in.GetUserProfileUseCase;
import com.socialcoisa.rede.profile.application.port.in.UpdateProfileUseCase;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

@RestController
@RequestMapping("/api")
class ProfileController {

	private final GetMeUseCase getMeUseCase;
	private final GetUserProfileUseCase getUserProfileUseCase;
	private final UpdateProfileUseCase updateProfileUseCase;
	private final CurrentUserResolver currentUserResolver;
	private final IdempotencyApplicationService idempotency;

	ProfileController(GetMeUseCase getMeUseCase, GetUserProfileUseCase getUserProfileUseCase,
			UpdateProfileUseCase updateProfileUseCase, CurrentUserResolver currentUserResolver,
			IdempotencyApplicationService idempotency) {
		this.getMeUseCase = getMeUseCase;
		this.getUserProfileUseCase = getUserProfileUseCase;
		this.updateProfileUseCase = updateProfileUseCase;
		this.currentUserResolver = currentUserResolver;
		this.idempotency = idempotency;
	}

	@GetMapping("/me")
	Object me(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		return getMeUseCase.getMe(user.id().value());
	}

	@GetMapping("/users/{username}")
	Object user(@PathVariable String username) {
		return getUserProfileUseCase.getByUsername(username);
	}

	@PatchMapping("/me/profile")
	ResponseEntity<String> update(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody UpdateProfileRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "PATCH", "/api/me/profile", idempotencyKey, hash,
			() -> new CommandResponse(200, updateProfileUseCase.update(new UpdateProfileCommand(user.id().value(),
				request.name(), request.headline(), request.company(), request.location(), request.avatar(),
				request.banner(), request.bio(), request.skills(), request.goals(), request.availability(),
				request.portfolio(), request.externalLinks(), request.openToWork(), request.isMentor()))));
	}
}
