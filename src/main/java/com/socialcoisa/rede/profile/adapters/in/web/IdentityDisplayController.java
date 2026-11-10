package com.socialcoisa.rede.profile.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.profile.application.UpdateIdentityDisplayService;
import com.socialcoisa.rede.profile.application.UserProfileView;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Identidade exibida (apelido, nome social, pronomes).
 *
 * Controller proprio em vez de entrar no fluxo de edicao de perfil: sao preferencias
 * de identidade com regras proprias (o nome social substitui o de cadastro para todo
 * mundo), e o fluxo de edicao esta em manutencao por outra sessao.
 */
@RestController
class IdentityDisplayController {

	private final UpdateIdentityDisplayService service;
	private final CurrentUserResolver currentUserResolver;

	IdentityDisplayController(UpdateIdentityDisplayService service, CurrentUserResolver currentUserResolver) {
		this.service = service;
		this.currentUserResolver = currentUserResolver;
	}

	@PutMapping("/me/badges")
	UserProfileView updateBadges(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody UpdateBadgesRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return UserProfileView.from(service.updateBadges(user.id().value(), request.badges()));
	}

	/**
	 * Histórico dos últimos 3 kits de emblemas do próprio perfil, do mais recente para o mais
	 * antigo. O histórico é alimentado no momento em que a lista muda.
	 */
	@GetMapping("/me/badge-kits")
	List<BadgeKitView> badgeKits(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return service.badgeHistory(user.id().value()).stream().map(BadgeKitView::from).toList();
	}

	/** Volta a exibir um kit do próprio histórico (posse e limite são revalidados). */
	@PostMapping("/me/badge-kits/{kitId}/apply")
	UserProfileView applyBadgeKit(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String kitId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return UserProfileView.from(service.applyBadgeKit(user.id().value(), kitId));
	}

	@PutMapping("/me/identity")
	UserProfileView update(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody UpdateIdentityRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return UserProfileView.from(service.update(user.id().value(), request.nickname(), request.socialName(),
			request.pronouns()));
	}
}
