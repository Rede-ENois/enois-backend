package com.socialcoisa.rede.services.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.services.application.ServiceOfferService;
import com.socialcoisa.rede.services.application.ServiceView;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Diretório de serviços da comunidade — a mesma mecânica dos jogos:
 * catálogo público por categoria, envio, moderação e página do serviço.
 */
@RestController
@RequestMapping("/services")
class ServiceOfferController {

	private final ServiceOfferService services;
	private final CurrentUserResolver currentUserResolver;

	ServiceOfferController(ServiceOfferService services, CurrentUserResolver currentUserResolver) {
		this.services = services;
		this.currentUserResolver = currentUserResolver;
	}

	record CreateRequest(String title, String description, String link, String category) {
	}

	/** Catálogo público: só os publicados, filtráveis por categoria e busca. Visitante lê. */
	@GetMapping
	List<ServiceView> catalog(@RequestParam(required = false) String category,
			@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "30") int limit,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		String viewerId = viewerIdOrNull(sessionToken);
		var ofertas = services.catalog(category, q, limit);
		var handles = services.usernamesOf(ofertas);
		return ofertas.stream()
			.map(offer -> ServiceView.of(offer, handles.get(offer.userId()), viewerId))
			.toList();
	}

	@GetMapping("/{username}/{serviceSlug}")
	ServiceView bySlug(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String username, @PathVariable String serviceSlug) {
		String viewerId = viewerIdOrNull(sessionToken);
		var offer = services.byUserAndSlug(username, serviceSlug, viewerId)
			.orElseThrow(() -> new ApiException(404, "SERVICE_NOT_FOUND", "Serviço não encontrado."));
		return ServiceView.of(offer, username, viewerId);
	}

	/** A lista de quem está logado — todos os status, para acompanhar a moderação. */
	@GetMapping("/mine")
	List<ServiceView> mine(@RequestHeader("X-Session-Token") String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		var ofertas = services.mine(user.id().value());
		var handles = services.usernamesOf(ofertas);
		return ofertas.stream()
			.map(offer -> ServiceView.of(offer, handles.get(offer.userId()), user.id().value()))
			.toList();
	}

	@PostMapping
	Map<String, Object> submit(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestBody CreateRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		var created = services.submit(user.id().value(), request.title(), request.description(),
			request.link(), request.category());
		return Map.of("id", created.id(), "slug", created.slug(), "status", created.status().name());
	}

	@PatchMapping("/{serviceId}")
	Map<String, Object> edit(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String serviceId,
			@RequestBody CreateRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		var edited = services.edit(serviceId, user.id().value(), request.title(), request.description(),
			request.link(), request.category());
		return Map.of("id", edited.id(), "slug", edited.slug(), "status", edited.status().name());
	}

	@DeleteMapping("/{serviceId}")
	Map<String, Object> remove(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String serviceId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		services.delete(serviceId, user.id().value());
		return Map.of("deleted", true);
	}

	@PostMapping("/{serviceId}/resubmit")
	Map<String, Object> resubmit(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String serviceId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		var resubmitted = services.resubmit(serviceId, user.id().value());
		return Map.of("id", resubmitted.id(), "status", resubmitted.status().name());
	}

	private String viewerIdOrNull(String sessionToken) {
		try {
			return currentUserResolver.resolve(sessionToken).id().value();
		}
		catch (Exception ignored) {
			return null;
		}
	}

}
