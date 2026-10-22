package com.socialcoisa.rede.themes.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.themes.application.ThemeGalleryService;
import com.socialcoisa.rede.themes.domain.ThemePackage;

/** Galeria de temas da comunidade: publicar (autor), listar/instalar/denunciar (todos). */
@RestController
class ThemeGalleryController {

	private final ThemeGalleryService gallery;
	private final CurrentUserResolver currentUserResolver;

	ThemeGalleryController(ThemeGalleryService gallery, CurrentUserResolver currentUserResolver) {
		this.gallery = gallery;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping("/temas/galeria")
	List<ThemeGalleryView> list(@RequestParam(defaultValue = "60") int limit) {
		// Teto: cada tema carrega o pacote JSON inteiro, e a galeria e publica.
		return gallery.list(Math.min(Math.max(limit, 1), 100)).stream().map(ThemeGalleryView::from).toList();
	}

	@GetMapping("/temas/galeria/{themeId}")
	ThemeGalleryView get(@PathVariable String themeId) {
		// A mesma forma da listagem: o pacote sai como `packageJson`, a chave que o front e a
		// documentacao publica ja usam (a preview do marketplace tambem le daqui).
		return ThemeGalleryView.from(gallery.get(themeId));
	}

	@PostMapping("/temas/galeria")
	Map<String, Object> publish(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody PublishThemeRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		ThemePackage created = gallery.publish(user.id().value(), request.name(), request.description(),
			request.packageJson(), request.modeDefault());
		return Map.of("id", created.id(), "status", created.status().name());
	}

	@PatchMapping("/temas/galeria/{themeId}")
	ThemeGalleryView update(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String themeId, @RequestBody UpdateThemeRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		return ThemeGalleryView.from(gallery.update(themeId, user.id().value(), request.name(), request.description(),
			request.packageJson(), request.modeDefault()));
	}

	@GetMapping("/temas/galeria/mine")
	List<ThemeGalleryView> mine(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		return gallery.mine(user.id().value()).stream().map(ThemeGalleryView::from).toList();
	}

	@PostMapping("/temas/galeria/{themeId}/install")
	Map<String, Object> install(@PathVariable String themeId) {
		gallery.install(themeId);
		return Map.of("status", "INSTALLED");
	}

	@PostMapping("/temas/galeria/{themeId}/report")
	Map<String, Object> report(@PathVariable String themeId) {
		gallery.report(themeId);
		return Map.of("status", "REPORTED");
	}

	@PostMapping("/temas/galeria/{themeId}/hide")
	Map<String, Object> hide(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String themeId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		gallery.hide(themeId, user.id().value());
		return Map.of("status", "HIDDEN");
	}

	record PublishThemeRequest(String name, String description, String packageJson, String modeDefault) {
	}

	/** PATCH: o que ficar de fora do corpo permanece como esta. */
	record UpdateThemeRequest(String name, String description, String packageJson, String modeDefault) {
	}

	record ThemeGalleryView(String id, String name, String description, String packageJson, String modeDefault,
			int installs, String createdAt) {
		static ThemeGalleryView from(ThemePackage t) {
			return new ThemeGalleryView(t.id(), t.name(), t.description() == null ? "" : t.description(),
				t.packageJson(), t.modeDefault(), t.installs(), t.createdAt().toString());
		}
	}
}
