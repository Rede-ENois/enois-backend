package com.socialcoisa.rede.cosmetics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.cosmetics.application.CosmeticCatalog.CatalogFile;
import com.socialcoisa.rede.cosmetics.application.CosmeticCatalog.CollectionFile;
import com.socialcoisa.rede.cosmetics.application.CosmeticCatalog.Item;
import com.socialcoisa.rede.cosmetics.application.CosmeticCatalog.LookFile;
import com.socialcoisa.rede.cosmetics.application.CosmeticCatalog.WindowFile;
import com.socialcoisa.rede.cosmetics.domain.CosmeticKind;

import tools.jackson.databind.json.JsonMapper;

/** The catalog resource is validated at startup: a broken file must stop the boot. */
class CosmeticCatalogTests {

	private static final List<CollectionFile> SACI = List.of(
		new CollectionFile("saci", "Dia do Saci", List.of(new WindowFile("10-15", "11-02"))));

	private static Item item(int order, String id, CosmeticKind kind, String collection) {
		return new Item(order, id, kind, collection, id, "", 1_000, null, true, kind == CosmeticKind.BADGE ? "pe" : null);
	}

	@Test
	void shippedCatalogLoadsWithEveryCollectionAndLook() {
		CosmeticCatalog catalog = new CosmeticCatalog(JsonMapper.builder().build());
		assertThat(catalog.items()).hasSize(361);
		assertThat(catalog.collections()).hasSize(13);
		assertThat(catalog.item("moldura-ambar").orElseThrow().releaseOrder()).isEqualTo(1);
		assertThat(catalog.item("moldura-radar").orElseThrow().releaseOrder()).isEqualTo(26);
		assertThat(catalog.item("emblema-time-saci").orElseThrow().purchasable()).isFalse();
		assertThat(catalog.look("look-saci").orElseThrow().items()).first().isEqualTo("tema-saci");
		// A seasonal collection carries just its own look; the ready-made ones live in Essenciais.
		assertThat(catalog.looksOf("saci")).extracting(CosmeticCatalog.Look::id).containsExactly("look-saci");
		assertThat(catalog.looksOf("carnaval")).extracting(CosmeticCatalog.Look::id).containsExactly("look-carnaval");
		assertThat(catalog.look("look-carnaval").orElseThrow().title()).isEqualTo("Destaque");
		assertThat(catalog.look("look-carnaval").orElseThrow().items()).first().isEqualTo("tema-carnaval");
		// Original frames are always on sale, whatever the date.
		assertThat(catalog.available(catalog.item("moldura-ambar").orElseThrow(), Instant.parse("2026-04-01T12:00:00Z")))
			.isTrue();
		assertThat(catalog.available(catalog.item("tema-saci").orElseThrow(), Instant.parse("2026-04-01T12:00:00Z")))
			.isFalse();
	}

	@Test
	void essenciaisStaysOnSaleAllYearAndItsLooksAreReadyMade() {
		CosmeticCatalog catalog = new CosmeticCatalog(JsonMapper.builder().build());
		CosmeticCatalog.Collection essenciais = catalog.collection("essenciais");
		assertThat(essenciais).isNotNull();
		for (String iso : List.of("2026-01-01T12:00:00Z", "2026-04-01T12:00:00Z", "2026-07-15T12:00:00Z",
				"2026-12-31T12:00:00Z")) {
			Instant now = Instant.parse(iso);
			assertThat(catalog.available(essenciais, now)).as(iso).isTrue();
			assertThat(catalog.available(catalog.item("tema-papel").orElseThrow(), now)).as(iso).isTrue();
		}
		assertThat(catalog.looksOf("essenciais")).extracting(CosmeticCatalog.Look::id)
			.containsExactly("look-papel", "look-noite-limpa", "look-jardim", "look-fim-de-tarde",
					"look-manha-de-domingo", "look-carta-na-gaveta", "look-sombra-fresca",
					"look-quintal-ao-meio-dia", "look-pedra-fria", "look-nevoeiro", "look-mar-de-pedra");
		assertThat(catalog.looksOf("essenciais")).allSatisfy(look -> assertThat(look.items())
			.allSatisfy(itemId -> assertThat(catalog.item(itemId).orElseThrow().purchasable()).isTrue()));
		// Each ready-made look has its own combination, so the shop never shows the same bundle twice.
		assertThat(catalog.looksOf("essenciais")).extracting(CosmeticCatalog.Look::items).doesNotHaveDuplicates();
		// The permanent collections are listed last so the shop still opens on the one in season; out
		// of season they are the only ones available, so the first of them becomes the default.
		assertThat(catalog.collections()).extracting(CosmeticCatalog.Collection::id)
			.endsWith("essenciais", "aparencias");
	}

	@Test
	void everyAppearanceThemeHasItsOwnLookOnSaleAllYear() {
		CosmeticCatalog catalog = new CosmeticCatalog(JsonMapper.builder().build());
		List<CosmeticCatalog.Look> looks = catalog.looksOf("aparencias");
		assertThat(looks).hasSize(85);
		assertThat(looks).extracting(CosmeticCatalog.Look::title).doesNotContain("Blahaj");
		// One look per theme of the appearance gallery: its card theme comes first, the theme of the
		// screen the look was drawn for.
		assertThat(looks).allSatisfy(look -> {
			Item theme = catalog.item(look.items().get(0)).orElseThrow();
			assertThat(theme.kind()).isEqualTo(CosmeticKind.CARD_THEME);
			assertThat(theme.collection()).isEqualTo("aparencias");
		});
		assertThat(looks).extracting(look -> look.items().get(0)).doesNotHaveDuplicates();
		assertThat(catalog.items().stream().filter(item -> "aparencias".equals(item.collection())).count()).isEqualTo(85);
		for (String iso : List.of("2026-01-01T12:00:00Z", "2026-04-01T12:00:00Z", "2026-11-20T12:00:00Z")) {
			Instant now = Instant.parse(iso);
			assertThat(looks).as(iso).allSatisfy(look -> assertThat(catalog.available(catalog.collection(look.collection()),
				now)).isTrue());
		}
		// Every piece is permanent, so a look never leaves the shop with a season.
		assertThat(looks).allSatisfy(look -> assertThat(look.items()).allSatisfy(itemId -> {
			Item item = catalog.item(itemId).orElseThrow();
			assertThat(item.purchasable()).isTrue();
			assertThat(item.collection()).isIn(null, "aparencias", "essenciais");
		}));
		// The theme is the look, so no two looks are the same bundle.
		assertThat(looks).extracting(CosmeticCatalog.Look::items).doesNotHaveDuplicates();
		assertThat(looks).extracting(CosmeticCatalog.Look::statusText).doesNotHaveDuplicates();
	}

	@Test
	void gardenStylesAreAlwaysOnSale() {
		CosmeticCatalog catalog = new CosmeticCatalog(JsonMapper.builder().build());
		List<Item> gardens = catalog.items().stream().filter(item -> item.kind() == CosmeticKind.GARDEN).toList();
		assertThat(gardens).extracting(Item::id)
			.containsExactly("garden-cerrado", "garden-mata-atlantica", "garden-caatinga", "garden-pampa");
		assertThat(gardens).allSatisfy(item -> {
			assertThat(item.collection()).isNull();
			assertThat(item.purchasable()).isTrue();
			assertThat(catalog.available(item, Instant.parse("2026-04-01T12:00:00Z"))).isTrue();
		});
	}

	@Test
	void seasonFollowsSaoPauloDateNotUtc() {
		CosmeticCatalog catalog = new CosmeticCatalog(JsonMapper.builder().build());
		Item saci = catalog.item("tema-saci").orElseThrow();
		// 2026-11-03T01:00Z is still Nov 2nd, 22:00, in São Paulo: the last day of the season.
		assertThat(catalog.available(saci, Instant.parse("2026-11-03T01:00:00Z"))).isTrue();
		assertThat(catalog.available(saci, Instant.parse("2026-11-03T03:30:00Z"))).isFalse();
	}

	@Test
	void duplicatedIdFails() {
		assertThatThrownBy(() -> new CosmeticCatalog(new CatalogFile(SACI,
			List.of(item(1, "a", CosmeticKind.FRAME, null), item(2, "a", CosmeticKind.FRAME, null)), List.of())))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicated catalog id");
	}

	@Test
	void lookWithUnknownItemFails() {
		assertThatThrownBy(() -> new CosmeticCatalog(new CatalogFile(SACI,
			List.of(item(1, "tema-saci", CosmeticKind.CARD_THEME, "saci")),
			List.of(new LookFile(null, "saci", "Saci", List.of("tema-saci", "nao-existe"), "pe", "oi")))))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("nao-existe");
	}

	@Test
	void aNamedLookKeepsItsIdAndAnUnnamedOneFallsBackToTheCollection() {
		CosmeticCatalog catalog = new CosmeticCatalog(new CatalogFile(SACI,
			List.of(item(1, "tema-saci", CosmeticKind.CARD_THEME, "saci")),
			List.of(new LookFile(null, "saci", "Saci", List.of("tema-saci"), "pe", "oi"),
				new LookFile("look-mata-fechada", "saci", "Mata fechada", List.of("tema-saci"), "pe", "oi"))));
		assertThat(catalog.looksOf("saci")).extracting(CosmeticCatalog.Look::id)
			.containsExactly("look-saci", "look-mata-fechada");
	}

	@Test
	void duplicatedOrUnsafeLookIdFails() {
		assertThatThrownBy(() -> new CosmeticCatalog(new CatalogFile(SACI,
			List.of(item(1, "tema-saci", CosmeticKind.CARD_THEME, "saci")),
			List.of(new LookFile("look-repetido", "saci", "Um", List.of("tema-saci"), "pe", "oi"),
				new LookFile("look-repetido", "saci", "Dois", List.of("tema-saci"), "pe", "oi")))))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicated look");
		assertThatThrownBy(() -> new CosmeticCatalog(new CatalogFile(SACI,
			List.of(item(1, "tema-saci", CosmeticKind.CARD_THEME, "saci")),
			List.of(new LookFile("Look Saci", "saci", "Saci", List.of("tema-saci"), "pe", "oi")))))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid look id");
	}

	@Test
	void unparseableSeasonWindowFails() {
		assertThatThrownBy(() -> new CosmeticCatalog(new CatalogFile(
			List.of(new CollectionFile("saci", "Dia do Saci", List.of(new WindowFile("10-15", "11-31")))),
			List.of(), List.of())))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	void duplicatedReleaseOrderAndUnknownCollectionFail() {
		assertThatThrownBy(() -> new CosmeticCatalog(new CatalogFile(SACI,
			List.of(item(1, "a", CosmeticKind.FRAME, null), item(1, "b", CosmeticKind.FRAME, null)), List.of())))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new CosmeticCatalog(new CatalogFile(SACI,
			List.of(item(1, "a", CosmeticKind.FRAME, "natal")), List.of())))
			.isInstanceOf(IllegalStateException.class);
	}
}
