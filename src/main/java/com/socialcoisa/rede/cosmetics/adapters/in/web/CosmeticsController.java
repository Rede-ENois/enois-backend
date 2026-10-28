package com.socialcoisa.rede.cosmetics.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.socialcoisa.rede.cosmetics.application.CosmeticsService;
import com.socialcoisa.rede.profile.application.UserProfileView;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * A loja de cosméticos (plano C3): itens nativos com preço baixo em faíscas, coleções sazonais,
 * compra direta (auditada no ledger) e equipar. Só aparência — nunca alcance.
 */
@RestController
class CosmeticsController {

	private final CosmeticsService cosmetics;
	private final CurrentUserResolver currentUserResolver;

	CosmeticsController(CosmeticsService cosmetics, CurrentUserResolver currentUserResolver) {
		this.cosmetics = cosmetics;
		this.currentUserResolver = currentUserResolver;
	}

	/** O catálogo da loja; com sessão, marca o que a pessoa já tem e o que está equipado. */
	@GetMapping("/loja")
	List<ShopItemView> catalog(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String order) {
		String me = sessionToken == null ? null : currentUserResolver.resolve(sessionToken).id().value();
		return cosmetics.shop(me, order == null ? "oldest" : order).stream().map(ShopItemView::from).toList();
	}

	/** As coleções sazonais, as janelas de venda e os looks prontos de cada uma. */
	@GetMapping("/loja/colecoes")
	List<CollectionView> collections() {
		return cosmetics.collections().stream().map(CollectionView::from).toList();
	}

	@PostMapping("/loja/{itemId}/comprar")
	OwnedView buy(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String itemId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return new OwnedView(cosmetics.buy(user.id().value(), itemId).id(), true);
	}

	/** Compra as peças que faltam de um look (25% mais barato) e veste o look inteiro. */
	@PostMapping("/loja/looks/{lookId}/comprar")
	CosmeticsService.LookPurchase buyLook(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String lookId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return cosmetics.buyLook(user.id().value(), lookId);
	}

	@PostMapping("/loja/{itemId}/equipar")
	EquippedView equip(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String itemId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		cosmetics.equip(user.id().value(), itemId);
		return new EquippedView(itemId, !"none".equals(itemId));
	}

	@PostMapping("/loja/{itemId}/desequipar")
	EquippedView unequip(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String itemId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		cosmetics.unequip(user.id().value(), itemId);
		return new EquippedView(itemId, false);
	}

	/** O recado do perfil: texto curto e um ícone de emblema que a pessoa tem (ou um dos gratuitos). */
	@PutMapping("/me/status")
	UserProfileView updateStatus(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody StatusRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return UserProfileView.from(cosmetics.updateStatus(user.id().value(), request.text(), request.icon()));
	}

	record StatusRequest(String text, String icon) {
	}

	record ShopItemView(String id, String kind, String collection, String name, String description, long priceMf,
			String cssRing, int releaseOrder, boolean available, boolean purchasable,
			@JsonInclude(JsonInclude.Include.NON_NULL) Boolean owned,
			@JsonInclude(JsonInclude.Include.NON_NULL) Boolean equipped) {

		static ShopItemView from(CosmeticsService.ShopItem row) {
			var item = row.item();
			return new ShopItemView(item.id(), item.kind().name(), item.collection(), item.name(), item.description(),
				item.priceMf(), item.cssRing(), item.releaseOrder(), row.available(), item.purchasable(), row.owned(),
				row.equipped());
		}
	}

	record CollectionView(String id, String name, boolean available, List<WindowView> windows, List<LookView> looks) {

		static CollectionView from(CosmeticsService.CollectionRow row) {
			var collection = row.collection();
			return new CollectionView(collection.id(), collection.name(), row.available(),
				collection.windows().stream().map(window -> new WindowView(window.startText(), window.endText())).toList(),
				row.looks().stream().map(look -> new LookView(look.id(), look.title(), look.items(), look.statusIcon(),
					look.statusText())).toList());
		}
	}

	record WindowView(String start, String end) {
	}

	record LookView(String id, String title, List<String> items, String statusIcon, String statusText) {
	}

	record OwnedView(String id, boolean owned) {
	}

	record EquippedView(String id, boolean equipped) {
	}
}
