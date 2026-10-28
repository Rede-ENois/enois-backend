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
		assertThat(catalog.items()).hasSize(250);
		assertThat(catalog.collections()).hasSize(11);
		assertThat(catalog.item("moldura-ambar").orElseThrow().releaseOrder()).isEqualTo(1);
		assertThat(catalog.item("moldura-radar").orElseThrow().releaseOrder()).isEqualTo(26);
		assertThat(catalog.item("emblema-time-saci").orElseThrow().purchasable()).isFalse();
		assertThat(catalog.look("look-saci").orElseThrow().items()).first().isEqualTo("tema-saci");
		// Original frames are always on sale, whatever the date.
		assertThat(catalog.available(catalog.item("moldura-ambar").orElseThrow(), Instant.parse("2026-04-01T12:00:00Z")))
			.isTrue();
		assertThat(catalog.available(catalog.item("tema-saci").orElseThrow(), Instant.parse("2026-04-01T12:00:00Z")))
			.isFalse();
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
			List.of(new LookFile("saci", "Saci", List.of("tema-saci", "nao-existe"), "pe", "oi")))))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("nao-existe");
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
