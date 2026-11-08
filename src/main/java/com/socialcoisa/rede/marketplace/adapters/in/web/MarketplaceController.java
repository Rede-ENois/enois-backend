package com.socialcoisa.rede.marketplace.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.marketplace.application.MarketplaceService;
import com.socialcoisa.rede.marketplace.domain.MarketplaceListing;
import com.socialcoisa.rede.marketplace.domain.MarketplacePurchase;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.profile.application.NameStyleView;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.themes.application.ThemeGalleryService;
import com.socialcoisa.rede.themes.domain.ThemePackage;

/**
 * O marketplace de itens (Fase 4): listar um tema, comprar com escrow, confirmar
 * (libera para o criador −10%) ou pedir reembolso (devolve 100%, dentro de 7 dias).
 */
@RestController
class MarketplaceController {

	private final MarketplaceService marketplace;
	private final ThemeGalleryService gallery;
	private final CurrentUserResolver currentUserResolver;
	private final IdempotencyApplicationService idempotency;
	private final com.socialcoisa.rede.profile.application.port.out.UserProfileRepository profiles;
	private final com.socialcoisa.rede.marketplace.application.MarketplaceSanitizeWorker sanitizeWorker;
	private final com.socialcoisa.rede.shared.web.AdminGuard adminGuard;

	MarketplaceController(MarketplaceService marketplace, ThemeGalleryService gallery,
			CurrentUserResolver currentUserResolver,
			IdempotencyApplicationService idempotency,
			com.socialcoisa.rede.profile.application.port.out.UserProfileRepository profiles,
			com.socialcoisa.rede.marketplace.application.MarketplaceSanitizeWorker sanitizeWorker,
			com.socialcoisa.rede.shared.web.AdminGuard adminGuard) {
		this.marketplace = marketplace;
		this.gallery = gallery;
		this.currentUserResolver = currentUserResolver;
		this.idempotency = idempotency;
		this.profiles = profiles;
		this.sanitizeWorker = sanitizeWorker;
		this.adminGuard = adminGuard;
	}

	/** Limpeza sob demanda dos resquícios (admin). Devolve quantas listagens caíram. */
	@PostMapping("/admin/marketplace/sanitize")
	Map<String, Object> sanitize(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		int removed = sanitizeWorker.sanitizeNow();
		return Map.of("removed", removed, "listings", marketplace.catalog(60).size());
	}

	@GetMapping("/marketplace")
	List<ListingView> catalog(@RequestParam(defaultValue = "60") int limit,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String kind) {
		return views(marketplace.catalog(limit, q, kind));
	}

	/** Publica qualquer conteúdo (template, banner, página, post, blog ou serviço). */
	@PostMapping("/marketplace/items")
	Map<String, Object> publishItem(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody PublishItemRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		MarketplaceListing listing = marketplace.publishItem(user.id().value(), request.kind(), request.title(),
			request.description(), request.content(), request.price());
		return Map.of("id", listing.id(), "status", listing.status().name());
	}

	/** Conteúdo de uma compra (só o comprador): usado pelo editor de "Minhas compras". */
	@GetMapping("/marketplace/purchases/{purchaseId}/content")
	Map<String, Object> purchasedContent(@PathVariable String purchaseId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		var purchased = marketplace.contentForPurchase(purchaseId, user.id().value());
		return Map.of("kind", purchased.kind().name(), "title", purchased.title(), "itemId", purchased.itemId(),
			"content", purchased.content() == null ? "" : purchased.content());
	}

	@GetMapping("/marketplace/mine")
	List<ListingView> mine(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return views(marketplace.mine(user.id().value()));
	}

	@GetMapping("/marketplace/compras")
	List<PurchaseView> myPurchases(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return marketplace.purchasesDetailed(user.id().value()).stream().map(PurchaseView::from).toList();
	}

	@GetMapping("/marketplace/vendas")
	List<PurchaseView> mySales(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return marketplace.salesBySeller(user.id().value()).stream()
			.map(p -> PurchaseView.from(p, null, null, null))
			.toList();
	}

	@PostMapping("/marketplace")
	Map<String, Object> publish(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken, @RequestBody PublishRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		MarketplaceListing.ItemKind kind = parseKind(request.itemKind());
		if (kind != MarketplaceListing.ItemKind.THEME) {
			throw new ApiException(400, "INVALID_ITEM_KIND", "Por enquanto só temas estão à venda.");
		}
		ThemePackage theme = gallery.get(request.itemId());
		if (!user.id().value().equals(theme.authorId())) {
			throw new ApiException(403, "NOT_THE_AUTHOR", "Só quem publicou o tema pode listá-lo.");
		}
		MarketplaceListing listing = marketplace.publish(user.id().value(), request.itemKind(), request.itemId(),
			request.title() == null ? theme.name() : request.title(), request.description(), request.price());
		return Map.of("id", listing.id(), "status", listing.status().name());
	}

	@PostMapping("/marketplace/{listingId}/buy")
	ResponseEntity<String> buy(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String key, @PathVariable String listingId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return idempotency.execute(user.id(), "POST", "/marketplace/" + listingId + "/buy", key,
			idempotency.hash(Map.of()), () -> {
				MarketplacePurchase purchase = marketplace.buy(listingId, user.id().value());
				return new CommandResponse(200, Map.of("purchaseId", purchase.id(),
					"state", purchase.state().name(), "price", purchase.price()));
			});
	}

	@PostMapping("/marketplace/purchases/{purchaseId}/confirm")
	ResponseEntity<String> confirm(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String key, @PathVariable String purchaseId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return idempotency.execute(user.id(), "POST", "/marketplace/purchases/" + purchaseId + "/confirm", key,
			idempotency.hash(Map.of()), () -> {
				MarketplacePurchase purchase = marketplace.confirm(purchaseId, user.id().value());
				return new CommandResponse(200, Map.of("purchaseId", purchase.id(), "state", purchase.state().name()));
			});
	}

	@PostMapping("/marketplace/purchases/{purchaseId}/refund")
	ResponseEntity<String> refund(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String key, @PathVariable String purchaseId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return idempotency.execute(user.id(), "POST", "/marketplace/purchases/" + purchaseId + "/refund", key,
			idempotency.hash(Map.of()), () -> {
				MarketplacePurchase purchase = marketplace.refund(purchaseId, user.id().value());
				return new CommandResponse(200, Map.of("purchaseId", purchase.id(), "state", purchase.state().name()));
			});
	}

	private MarketplaceListing.ItemKind parseKind(String raw) {
		if (raw == null) {
			return MarketplaceListing.ItemKind.THEME;
		}
		return MarketplaceListing.ItemKind.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
	}

	record PublishRequest(String itemKind, String itemId, String title, String description, long price) {
	}

	record PurchaseView(String id, String listingId, String buyerId, String sellerId, long price, String state,
			String createdAt, String decidedAt, String itemKind, String title, String itemId) {
		static PurchaseView from(MarketplacePurchase p, String itemKind, String title, String itemId) {
			return new PurchaseView(p.id(), p.listingId(), p.buyerId(), p.sellerId(), p.price(), p.state().name(),
				p.createdAt().toString(), p.decidedAt() == null ? null : p.decidedAt().toString(), itemKind, title,
				itemId);
		}

		static PurchaseView from(MarketplaceService.PurchaseDetail detail) {
			MarketplacePurchase p = detail.purchase();
			return new PurchaseView(p.id(), p.listingId(), p.buyerId(), p.sellerId(), p.price(), p.state().name(),
				p.createdAt().toString(), p.decidedAt() == null ? null : p.decidedAt().toString(),
				detail.kind() == null ? null : detail.kind().name(), detail.title(), detail.itemId());
		}
	}

	record PublishItemRequest(String kind, String title, String description, String content, long price) {
	}

	/** Listagens com o nome de quem publicou (o card mostra "por @fulano"). */
	private List<ListingView> views(List<MarketplaceListing> listings) {
		// Perfis dos vendedores numa consulta so: antes era uma por vendedor distinto, entao uma
		// pagina de 20 anuncios de 20 pessoas custava 20 idas ao banco.
		java.util.Set<String> sellerIds = listings.stream()
			.map(MarketplaceListing::sellerId)
			.filter(java.util.Objects::nonNull)
			.collect(java.util.stream.Collectors.toSet());
		java.util.Map<String, com.socialcoisa.rede.profile.domain.UserProfile> byId = sellerIds.isEmpty()
			? java.util.Map.of()
			: profiles.findAllByIds(sellerIds).stream()
				.collect(java.util.stream.Collectors.toMap(
					com.socialcoisa.rede.profile.domain.UserProfile::id, java.util.function.Function.identity()));
		return listings.stream().map(listing -> {
			var profile = byId.get(listing.sellerId());
			return new ListingView(listing.id(),
				profile == null ? null : profile.name(),
				profile == null ? null : profile.username(),
				profile == null ? null : NameStyleView.from(profile),
				listing.itemKind().name(), listing.itemId(), listing.title(), listing.description(),
				listing.price(), listing.status().name(), listing.createdAt().toString());
		}).toList();
	}

	record ListingView(String id, String sellerName, String sellerUsername,
			NameStyleView sellerStyle, String itemKind, String itemId, String title, String description, long price,
			String status, String createdAt) {
	}
}
