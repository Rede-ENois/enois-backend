package com.socialcoisa.rede.cosmetics.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.cosmetics.application.CommunityCosmeticsService;
import com.socialcoisa.rede.cosmetics.domain.CommunityCosmetic;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/** Contrato da criação, moderação e catálogo de cosméticos da comunidade. */
@RestController
@RequestMapping("/cosmetics")
class CommunityCosmeticsController {

	private final CommunityCosmeticsService cosmetics;
	private final CurrentUserResolver currentUserResolver;

	CommunityCosmeticsController(CommunityCosmeticsService cosmetics, CurrentUserResolver currentUserResolver) {
		this.cosmetics = cosmetics;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping("/catalog")
	List<CosmeticView> catalog(@RequestParam(defaultValue = "60") int limit,
		@RequestParam(required = false) String kind) {
		return cosmetics.catalog(limit, kind).stream().map(CosmeticView::from).toList();
	}

	@GetMapping("/{id}")
	CosmeticView get(@PathVariable String id) {
		return CosmeticView.from(cosmetics.getPublished(id));
	}

	@PostMapping("/drafts")
	CosmeticView create(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
		@RequestBody CreateCosmeticRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return CosmeticView.from(cosmetics.createDraft(user.id().value(), request.kind(), request.name(),
			request.description(), request.manifestJson(), request.license(), request.rightsConfirmed()));
	}

	@GetMapping("/mine")
	List<CosmeticView> mine(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return cosmetics.mine(user.id().value()).stream().map(CosmeticView::from).toList();
	}

	@PatchMapping("/{id}")
	CosmeticView update(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken, @PathVariable String id,
		@RequestBody UpdateCosmeticRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return CosmeticView.from(cosmetics.update(id, user.id().value(), request.name(), request.description(),
			request.manifestJson(), request.license(), request.rightsConfirmed()));
	}

	@PostMapping("/{id}/submit")
	Map<String, Object> submit(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken, @PathVariable String id) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		CommunityCosmetic submitted = cosmetics.submit(id, user.id().value());
		return Map.of("id", submitted.id(), "status", submitted.status().name());
	}

	@PostMapping("/{id}/report")
	Map<String, Object> report(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken, @PathVariable String id) {
		currentUserResolver.resolve(sessionToken);
		cosmetics.report(id);
		return Map.of("status", "REPORTED");
	}

	@PutMapping("/profile/name-style")
	NameStyleView updateNameStyle(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
		@RequestBody NameStyleRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		var profile = cosmetics.updateNameStyle(user.id().value(), request.theme(), request.font(), request.color());
		return new NameStyleView(profile.nameTheme(), profile.nameFont(), profile.nameColor());
	}

	record CreateCosmeticRequest(String kind, String name, String description, String manifestJson, String license,
		Boolean rightsConfirmed) {
	}

	record UpdateCosmeticRequest(String name, String description, String manifestJson, String license,
		Boolean rightsConfirmed) {
	}

	@PutMapping("/profile/contributor-tag")
	ContributorTagView updateContributorTagColor(
		@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
		@RequestBody ContributorTagRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		var profile = cosmetics.updateContributorTagColor(user.id().value(), request.color());
		return new ContributorTagView(profile.contributorTagColor());
	}

	record NameStyleRequest(String theme, String font, String color) {
	}

	record ContributorTagRequest(String color) {
	}

	record ContributorTagView(String color) {
	}

	record NameStyleView(String theme, String font, String color) {
	}

	record CosmeticView(String id, String kind, String name, String description, String manifestJson,
		String license, String status, int version, int reported, String createdAt, String updatedAt) {
		static CosmeticView from(CommunityCosmetic cosmetic) {
			return new CosmeticView(cosmetic.id(), cosmetic.kind().name(), cosmetic.name(),
				cosmetic.description() == null ? "" : cosmetic.description(), cosmetic.manifestJson(), cosmetic.license(),
				cosmetic.status().name(), cosmetic.version(), cosmetic.reported(), cosmetic.createdAt().toString(),
				cosmetic.updatedAt().toString());
		}
	}
}
