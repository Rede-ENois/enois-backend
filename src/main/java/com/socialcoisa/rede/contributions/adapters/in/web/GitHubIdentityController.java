package com.socialcoisa.rede.contributions.adapters.in.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.contributions.application.GitHubIdentityService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/** Estado do vínculo GitHub da conta logada (a vinculação em si usa o OAuth existente). */
@RestController
class GitHubIdentityController {

	private final GitHubIdentityService identities;
	private final CurrentUserResolver currentUserResolver;

	GitHubIdentityController(GitHubIdentityService identities, CurrentUserResolver currentUserResolver) {
		this.identities = identities;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping("/me/github")
	GitHubIdentityService.GitHubIdentityView status(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return identities.status(user.id().value());
	}
}
