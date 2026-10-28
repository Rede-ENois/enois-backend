package com.socialcoisa.rede.cosmetics.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.coin.application.port.out.CoinLedgerRepository;
import com.socialcoisa.rede.coin.domain.CoinEntry;
import com.socialcoisa.rede.cosmetics.application.CosmeticCatalog.Item;
import com.socialcoisa.rede.cosmetics.application.port.out.CosmeticGrantRepository;
import com.socialcoisa.rede.cosmetics.domain.CosmeticKind;
import com.socialcoisa.rede.profile.application.port.out.OfficialBadgeReader;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.text.PlainText;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A loja de cosméticos (plano C3, Fase 3 da economia): itens NATIVOS, com preço baixo em faíscas.
 * A compra é direta (não é escrow: é item da própria plataforma) e fica auditada no ledger; a posse
 * é o lançamento PURCHASE ou, para recompensa de evento, uma linha em cosmetic_grants.
 * Nunca: alcance, impulsionamento — só aparência.
 */
@Service
public class CosmeticsService implements OfficialBadgeReader {

	private static final String OWNERSHIP_PREFIX = "cosmetico:";
	private static final String OFFICIAL_BADGE_PREFIX = "emblema-";
	private static final int MAX_BADGES = 5;
	private static final int MAX_STATUS_CHARS = 60;
	private static final Set<String> FREE_STATUS_ICONS = Set.of("cafe", "estrela", "coracao", "chip");

	public record LookPurchase(String id, List<String> bought, long priceMf) {
	}

	/** One shop row: {@code owned}/{@code equipped} are null without a session. */
	public record ShopItem(Item item, boolean available, Boolean owned, Boolean equipped) {
	}

	/** One collection with its season state and looks. */
	public record CollectionRow(CosmeticCatalog.Collection collection, boolean available,
			List<CosmeticCatalog.Look> looks) {
	}

	private final CosmeticCatalog catalog;
	private final CoinService coins;
	private final CoinLedgerRepository ledger;
	private final CosmeticGrantRepository grants;
	private final UserProfileRepository profiles;
	private final Clock clock;

	public CosmeticsService(CosmeticCatalog catalog, CoinService coins, CoinLedgerRepository ledger,
			CosmeticGrantRepository grants, UserProfileRepository profiles, Clock clock) {
		this.catalog = catalog;
		this.coins = coins;
		this.ledger = ledger;
		this.grants = grants;
		this.profiles = profiles;
		this.clock = clock;
	}

	public List<Item> catalog(String order) {
		Comparator<Item> comparator = switch (order) {
			case "oldest" -> Comparator.comparingInt(Item::releaseOrder);
			case "newest" -> Comparator.comparingInt(Item::releaseOrder).reversed();
			default -> throw new ApiException(400, "INVALID_CATALOG_ORDER", "Escolha a ordem oldest ou newest.");
		};
		return catalog.items().stream().sorted(comparator).toList();
	}

	/**
	 * The shop for one viewer. Ownership and the equipped slots load once for the whole list (ledger
	 * refs, grants and the profile): never a query per item.
	 */
	@Transactional(readOnly = true)
	public List<ShopItem> shop(String viewerId, String order) {
		List<Item> items = catalog(order);
		Instant now = clock.instant();
		if (viewerId == null) {
			return items.stream().map(item -> new ShopItem(item, catalog.available(item, now), null, null)).toList();
		}
		Set<String> owned = ownedItemIds(viewerId);
		UserProfile profile = profiles.findById(viewerId).orElse(null);
		return items.stream().map(item -> new ShopItem(item, catalog.available(item, now), owned.contains(item.id()),
			profile != null && equipped(profile, item))).toList();
	}

	public List<CollectionRow> collections() {
		Instant now = clock.instant();
		return catalog.collections().stream().map(collection -> new CollectionRow(collection,
			catalog.available(collection, now), catalog.looksOf(collection.id()))).toList();
	}

	/** Everything a person owns: bought (ledger) or granted. Two queries, whatever the catalog size. */
	public Set<String> ownedItemIds(String userId) {
		String prefix = ownershipRef(userId, "");
		Set<String> owned = new HashSet<>(grants.itemIdsOf(userId));
		ledger.refIdsOf(userId, CoinEntry.Kind.PURCHASE).stream()
			.filter(ref -> ref.startsWith(prefix))
			.forEach(ref -> owned.add(ref.substring(prefix.length())));
		return owned;
	}

	public boolean owns(String userId, String itemId) {
		return ledger.existsByKindAndRefId(CoinEntry.Kind.PURCHASE, ownershipRef(userId, itemId))
			|| grants.exists(userId, itemId);
	}

	/** Compra direta: debita o comprador e credita a tesouraria — supply fixo preservado. */
	@Transactional
	public Item buy(String userId, String itemId) {
		Item item = item(itemId);
		if (!item.purchasable()) {
			throw new ApiException(403, "NOT_FOR_SALE", "Este item não está à venda.");
		}
		if (owns(userId, itemId)) {
			throw new ApiException(409, "ALREADY_OWNED", "Você já tem este item.");
		}
		if (!catalog.available(item, clock.instant())) {
			throw new ApiException(409, "OUT_OF_SEASON", "Este item está fora de temporada.");
		}
		coins.purchase(userId, item.priceMf(), ownershipRef(userId, itemId), "Loja: " + item.name());
		return item;
	}

	/**
	 * Buys every look item the person does not own, in one transaction, then equips the whole look
	 * and its status. The look costs 75% of the missing items, rounded to whole faíscas (the price the
	 * shop shows). Each item is still charged under its own ownership ref (75% of its price, the
	 * rounding difference on the priciest one), so a concurrent second purchase cannot charge twice.
	 */
	@Transactional
	public LookPurchase buyLook(String userId, String lookId) {
		CosmeticCatalog.Look look = catalog.look(lookId)
			.orElseThrow(() -> new ApiException(404, "ITEM_NOT_FOUND", "Look não encontrado."));
		Set<String> owned = ownedItemIds(userId);
		List<Item> missing = look.items().stream().filter(id -> !owned.contains(id)).map(this::item).toList();
		if (missing.isEmpty()) {
			throw new ApiException(409, "ALREADY_OWNED", "Você já tem todas as peças deste look.");
		}
		if (!catalog.available(catalog.collection(look.collection()), clock.instant())) {
			throw new ApiException(409, "OUT_OF_SEASON", "Este look está fora de temporada.");
		}
		long fullPrice = missing.stream().mapToLong(Item::priceMf).sum();
		long total = Math.round(fullPrice * 3 / 4000.0) * 1000;
		Item priciest = missing.stream().max(Comparator.comparingLong(Item::priceMf)).orElseThrow();
		long rounding = total - missing.stream().mapToLong(item -> item.priceMf() * 3 / 4).sum();
		for (Item item : missing) {
			long price = item.priceMf() * 3 / 4 + (item == priciest ? rounding : 0);
			coins.purchase(userId, price, ownershipRef(userId, item.id()), "Loja: " + look.title() + " - " + item.name());
		}
		UserProfile profile = profile(userId);
		Instant now = clock.instant();
		List<String> badges = new ArrayList<>();
		for (String id : look.items()) {
			Item item = item(id);
			if (item.kind() == CosmeticKind.BADGE) {
				badges.add(id);
			}
			else {
				applySlot(profile, item, true, now);
			}
		}
		profile.updateBadges(badges.subList(0, Math.min(badges.size(), MAX_BADGES)), now);
		profile.updateStatus(look.statusText(), look.statusIcon(), now);
		profiles.save(profile);
		return new LookPurchase(look.id(), missing.stream().map(Item::id).toList(), total);
	}

	/** Equipa um item que a pessoa tem; "none" desequipa a moldura (contrato antigo). */
	@Transactional
	public UserProfile equip(String userId, String itemId) {
		if ("none".equals(itemId)) {
			UserProfile profile = profile(userId);
			profile.updateAvatarFrame(null, clock.instant());
			return profiles.save(profile);
		}
		return setSlot(userId, itemId, true);
	}

	@Transactional
	public UserProfile unequip(String userId, String itemId) {
		return setSlot(userId, itemId, false);
	}

	/**
	 * The status bubble: up to 60 characters (cleaned) and an optional icon, which is a free icon or
	 * the icon of an official badge the person owns. Empty text clears it.
	 */
	@Transactional
	public UserProfile updateStatus(String userId, String text, String icon) {
		String cleanText = text == null || text.isBlank() ? null : text.trim();
		if (cleanText != null && cleanText.length() > MAX_STATUS_CHARS) {
			throw new ApiException(400, "FIELD_TOO_LONG", "O status pode ter no máximo 60 caracteres.");
		}
		String cleanIcon = icon == null || icon.isBlank() ? null : icon.trim();
		if (cleanIcon != null && !FREE_STATUS_ICONS.contains(cleanIcon)) {
			Set<String> owned = ownedItemIds(userId);
			boolean ownsIcon = catalog.items().stream().anyMatch(item -> item.kind() == CosmeticKind.BADGE
				&& cleanIcon.equals(item.icon()) && owned.contains(item.id()));
			if (!ownsIcon) {
				throw new ApiException(400, "INVALID_STATUS_ICON", "Ícone de status inválido.");
			}
		}
		UserProfile profile = profile(userId);
		profile.updateStatus(PlainText.defuse(cleanText), cleanIcon, clock.instant());
		return profiles.save(profile);
	}

	/** Official badges are catalog ids ({@code emblema-*}); a person may only show the ones they own. */
	@Override
	public boolean ownsAllOfficial(String userId, Collection<String> badges) {
		List<String> official = badges.stream().filter(badge -> badge != null && badge.startsWith(OFFICIAL_BADGE_PREFIX))
			.toList();
		if (official.isEmpty()) {
			return true;
		}
		Set<String> owned = ownedItemIds(userId);
		return official.stream().allMatch(id -> catalog.item(id).map(item -> item.kind() == CosmeticKind.BADGE)
			.orElse(false) && owned.contains(id));
	}

	private UserProfile setSlot(String userId, String itemId, boolean equip) {
		Item item = item(itemId);
		if (!owns(userId, itemId)) {
			throw new ApiException(403, "NOT_OWNED", "Você precisa ter o item antes de equipar.");
		}
		UserProfile profile = profile(userId);
		Instant now = clock.instant();
		if (item.kind() == CosmeticKind.BADGE) {
			List<String> badges = new ArrayList<>(profile.allBadges());
			badges.remove(itemId);
			if (equip) {
				if (badges.size() >= MAX_BADGES) {
					throw new ApiException(409, "MAX_BADGES", "São no máximo 5 emblemas.");
				}
				badges.addFirst(itemId);
			}
			profile.updateBadges(badges, now);
		}
		else {
			applySlot(profile, item, equip, now);
		}
		return profiles.save(profile);
	}

	/** Puts the item in its slot (equip) or empties the slot when it holds this item (unequip). */
	private static void applySlot(UserProfile profile, Item item, boolean equip, Instant now) {
		String id = item.id();
		switch (item.kind()) {
			case FRAME -> profile.updateAvatarFrame(swap(profile.avatarFrame(), id, equip), now);
			// A seasonal name style brings its own font and colors: a kept font or color would repaint it.
			case NAME_STYLE -> {
				if (equip) {
					profile.updateNameStyle(id, null, null, now);
				}
				else {
					profile.updateNameStyle(swap(profile.nameTheme(), id, false), profile.nameFont(), profile.nameColor(),
						now);
				}
			}
			case CARD_THEME, NAMEPLATE, BANNER, PROFILE_EFFECT -> profile.updateProfileCosmetics(
				item.kind() == CosmeticKind.CARD_THEME ? swap(profile.cardTheme(), id, equip) : profile.cardTheme(),
				item.kind() == CosmeticKind.NAMEPLATE ? swap(profile.nameplate(), id, equip) : profile.nameplate(),
				item.kind() == CosmeticKind.BANNER ? swap(profile.bannerItem(), id, equip) : profile.bannerItem(),
				item.kind() == CosmeticKind.PROFILE_EFFECT ? swap(profile.profileEffect(), id, equip)
					: profile.profileEffect(),
				now);
			case BADGE -> throw new IllegalArgumentException("Badges go to the badge list.");
		}
	}

	private static String swap(String current, String id, boolean equip) {
		if (equip) {
			return id;
		}
		return id.equals(current) ? null : current;
	}

	private static boolean equipped(UserProfile profile, Item item) {
		String id = item.id();
		return switch (item.kind()) {
			case FRAME -> id.equals(profile.avatarFrame());
			case BADGE -> profile.allBadges().contains(id);
			case NAME_STYLE -> id.equals(profile.nameTheme());
			case NAMEPLATE -> id.equals(profile.nameplate());
			case BANNER -> id.equals(profile.bannerItem());
			case CARD_THEME -> id.equals(profile.cardTheme());
			case PROFILE_EFFECT -> id.equals(profile.profileEffect());
		};
	}

	private UserProfile profile(String userId) {
		return profiles.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Perfil não encontrado."));
	}

	private Item item(String itemId) {
		return catalog.item(itemId)
			.orElseThrow(() -> new ApiException(404, "ITEM_NOT_FOUND", "Item não encontrado."));
	}

	private static String ownershipRef(String userId, String itemId) {
		return OWNERSHIP_PREFIX + userId + ":" + itemId;
	}
}
