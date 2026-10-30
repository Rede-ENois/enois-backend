package com.socialcoisa.rede.corporate.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.corporate.application.CorporateProfileService;
import com.socialcoisa.rede.corporate.domain.CorporateProfile;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Endpoints do perfil profissional (o alterego corporativo da conta logada).
 *
 * O perfil é resolvido pela SESSÃO no backend — o payload nunca diz de quem é o perfil.
 * Estado e regras de ativação moram no serviço; aqui é só tradução HTTP.
 */
@RestController
class CorporateProfileController {

	private final CorporateProfileService service;
	private final CurrentUserResolver currentUserResolver;

	CorporateProfileController(CorporateProfileService service, CurrentUserResolver currentUserResolver) {
		this.service = service;
		this.currentUserResolver = currentUserResolver;
	}

	record CreateRequest(String username, String name) {
	}

	record DetailsRequest(String headline, String bio, String avatar, String banner, String bannerHtml, String location,
			String industry, String externalLinks) {
	}

	record NameRequest(String name) {
	}

	record UsernameRequest(String username) {
	}

	record ImportRequest(List<String> fields) {
	}

	@GetMapping("/me/business-profile")
	Map<String, Object> me(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return view(service.byUser(user.id().value()).orElse(null));
	}

	@GetMapping("/corporate/{username}")
	Map<String, Object> byUsername(@PathVariable String username) {
		// Leitura pública: só perfis ATIVOS têm página (draft/desativado = 404).
		CorporateProfile profile = service.byUsername(username)
			.filter(CorporateProfile::active)
			.orElseThrow(() -> new ApiException(404, "PROFILE_NOT_FOUND", "Perfil profissional não encontrado."));
		return view(profile);
	}

	@PostMapping("/me/business-profile")
	Map<String, Object> create(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken, @RequestBody CreateRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		CorporateProfile created = service.create(user.id().value(), request.username(), request.name());
		return view(created);
	}

	@PatchMapping("/me/business-profile")
	Map<String, Object> updateDetails(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken, @RequestBody DetailsRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		CorporateProfile updated = service.updateDetails(user.id().value(), request.headline(), request.bio(),
			request.avatar(), request.banner(), request.bannerHtml(), request.location(), request.industry(),
			request.externalLinks());
		return view(updated);
	}

	@PostMapping("/me/business-profile/name")
	Map<String, Object> rename(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken, @RequestBody NameRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return view(service.rename(user.id().value(), request.name()));
	}

	/**
	 * Troca o nametag do perfil profissional — o `@` e o fim da URL pública
	 * (`/profissional/{nametag}`). É independente do @ do perfil social.
	 */
	@PostMapping("/me/business-profile/username")
	Map<String, Object> changeUsername(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken, @RequestBody UsernameRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return view(service.changeUsername(user.id().value(), request.username()));
	}

	/** Prévia da importação do perfil social — nada é gravado. */
	@GetMapping("/me/business-profile/import-preview")
	CorporateProfileService.ImportPreview importPreview(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return service.importPreview(user.id().value());
	}

	/** Aplica só os campos escolhidos (consentimento campo a campo). */
	@PostMapping("/me/business-profile/import")
	Map<String, Object> importFromSocial(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken, @RequestBody ImportRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return view(service.importFromSocial(user.id().value(), request == null ? null : request.fields()));
	}

	@PostMapping("/me/business-profile/activate")
	Map<String, Object> activate(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return view(service.activate(user.id().value()));
	}

	@PostMapping("/me/business-profile/deactivate")
	Map<String, Object> deactivate(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return view(service.deactivate(user.id().value()));
	}

	private Map<String, Object> view(CorporateProfile profile) {
		if (profile == null) {
			return Map.of("exists", false);
		}
		return Map.ofEntries(
			Map.entry("exists", true),
			Map.entry("username", profile.username()),
			Map.entry("name", profile.name()),
			Map.entry("headline", profile.headline() == null ? "" : profile.headline()),
			Map.entry("bio", profile.bio() == null ? "" : profile.bio()),
			Map.entry("avatar", profile.avatar() == null ? "" : profile.avatar()),
			Map.entry("banner", profile.banner() == null ? "" : profile.banner()),
			Map.entry("bannerHtml", profile.bannerHtml() == null ? "" : profile.bannerHtml()),
			Map.entry("location", profile.location() == null ? "" : profile.location()),
			Map.entry("industry", profile.industry() == null ? "" : profile.industry()),
			Map.entry("externalLinks", profile.externalLinks() == null ? "" : profile.externalLinks()),
			Map.entry("status", profile.status().name()));
	}
}
