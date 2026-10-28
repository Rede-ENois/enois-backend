package com.socialcoisa.rede.cosmetics.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.cosmetics.domain.CosmeticKind;
import com.socialcoisa.rede.cosmetics.domain.SeasonWindow;

import tools.jackson.databind.ObjectMapper;

/**
 * The native shop catalog, loaded once from {@code cosmetics/catalog.json}. The resource is the
 * single source of ids, prices, collections and looks; a broken file stops the boot instead of
 * serving a half catalog.
 */
@Component
public class CosmeticCatalog {

	/** Seasons and events follow the Brazilian calendar, not the server zone. */
	public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	static final String RESOURCE = "cosmetics/catalog.json";

	/** One shop item. {@code collection} is null for the original frames, which are always on sale. */
	public record Item(int releaseOrder, String id, CosmeticKind kind, String collection, String name,
			String description, long priceMf, String cssRing, boolean purchasable, String icon) {
		public Item {
			cssRing = cssRing == null ? "" : cssRing;
		}
	}

	public record Collection(String id, String name, List<SeasonWindow> windows) {
		public boolean availableOn(MonthDay day) {
			return windows.stream().anyMatch(window -> window.contains(day));
		}
	}

	/** A ready-made outfit: theme, frame, name style, effect, banner (optional), plate, then badges. */
	public record Look(String id, String collection, String title, List<String> items, String statusIcon,
			String statusText) {
	}

	record WindowFile(String start, String end) {
	}

	record CollectionFile(String id, String name, List<WindowFile> windows) {
	}

	record LookFile(String collection, String title, List<String> items, String statusIcon, String statusText) {
	}

	record CatalogFile(List<CollectionFile> collections, List<Item> items, List<LookFile> looks) {
	}

	private final List<Item> items;
	private final Map<String, Item> itemsById;
	private final Map<String, Collection> collections;
	private final Map<String, Look> looks;

	@Autowired
	public CosmeticCatalog(ObjectMapper objectMapper) {
		this(read(objectMapper));
	}

	CosmeticCatalog(CatalogFile file) {
		Map<String, Collection> collectionsById = new LinkedHashMap<>();
		for (CollectionFile collection : file.collections()) {
			List<SeasonWindow> windows = collection.windows().stream()
				.map(window -> SeasonWindow.parse(window.start(), window.end())).toList();
			if (windows.isEmpty() || collectionsById.put(collection.id(),
					new Collection(collection.id(), collection.name(), windows)) != null) {
				throw new IllegalStateException("Invalid or duplicated collection: " + collection.id());
			}
		}
		Map<String, Item> byId = new LinkedHashMap<>();
		Set<Integer> releaseOrders = new HashSet<>();
		for (Item item : file.items()) {
			if (item.id() == null || item.kind() == null || byId.put(item.id(), item) != null) {
				throw new IllegalStateException("Invalid or duplicated catalog id: " + item.id());
			}
			// The id is what the profile stores: it must fit the slot column (users.name_theme is 24).
			if (item.id().length() > (item.kind() == CosmeticKind.NAME_STYLE ? 24 : 40)) {
				throw new IllegalStateException("Catalog id too long for its profile slot: " + item.id());
			}
			if (item.releaseOrder() <= 0 || !releaseOrders.add(item.releaseOrder())) {
				throw new IllegalStateException("Invalid or duplicated release order: " + item.id());
			}
			if (item.collection() != null && !collectionsById.containsKey(item.collection())) {
				throw new IllegalStateException("Unknown collection for " + item.id());
			}
			if (item.purchasable() && item.priceMf() <= 0) {
				throw new IllegalStateException("Item for sale without a price: " + item.id());
			}
			if (item.kind() == CosmeticKind.BADGE && item.icon() == null) {
				throw new IllegalStateException("Badge without an icon: " + item.id());
			}
		}
		Map<String, Look> looksById = new LinkedHashMap<>();
		for (LookFile look : file.looks()) {
			String id = "look-" + look.collection();
			if (!collectionsById.containsKey(look.collection()) || look.items().isEmpty()) {
				throw new IllegalStateException("Invalid look: " + id);
			}
			for (String itemId : look.items()) {
				Item item = byId.get(itemId);
				if (item == null || !item.purchasable()) {
					throw new IllegalStateException("Look " + id + " has an unknown or unsellable item: " + itemId);
				}
			}
			if (looksById.put(id, new Look(id, look.collection(), look.title(), List.copyOf(look.items()),
					look.statusIcon(), look.statusText())) != null) {
				throw new IllegalStateException("Duplicated look: " + id);
			}
		}
		this.items = byId.values().stream().sorted(Comparator.comparingInt(Item::releaseOrder)).toList();
		this.itemsById = Map.copyOf(byId);
		this.collections = java.util.Collections.unmodifiableMap(collectionsById);
		this.looks = java.util.Collections.unmodifiableMap(looksById);
	}

	private static CatalogFile read(ObjectMapper objectMapper) {
		try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
			return objectMapper.readValue(in, CatalogFile.class);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Cosmetics catalog not readable: " + RESOURCE, e);
		}
	}

	/** Every item, oldest release first. */
	public List<Item> items() {
		return items;
	}

	public Optional<Item> item(String id) {
		return Optional.ofNullable(id == null ? null : itemsById.get(id));
	}

	public List<Collection> collections() {
		return List.copyOf(collections.values());
	}

	public Optional<Look> look(String id) {
		return Optional.ofNullable(id == null ? null : looks.get(id));
	}

	public List<Look> looksOf(String collectionId) {
		return looks.values().stream().filter(look -> look.collection().equals(collectionId)).toList();
	}

	/** Original frames are always on sale; seasonal items only inside one of their windows. */
	public boolean available(Item item, Instant now) {
		return item.collection() == null || available(collections.get(item.collection()), now);
	}

	public boolean available(Collection collection, Instant now) {
		return collection.availableOn(MonthDay.from(now.atZone(ZONE)));
	}

	public Collection collection(String id) {
		return collections.get(id);
	}
}
