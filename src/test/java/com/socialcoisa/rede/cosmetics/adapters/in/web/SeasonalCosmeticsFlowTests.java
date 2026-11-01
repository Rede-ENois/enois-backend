package com.socialcoisa.rede.cosmetics.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.cosmetics.application.CosmeticsService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The seasonal shop on the duel day (2026-10-31): collections in and out of season, rewards that
 * are never sold, looks, every profile slot, the status bubble and seasonal name styles.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SeasonalTestClock.class)
class SeasonalCosmeticsFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private SeasonalTestClock.MovableClock clock;

	@Autowired
	private CosmeticsService cosmetics;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		clock.set(SeasonalTestClock.DUEL_DAY);
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private TestAuthHelper.AuthSession wallet(String name) throws Exception {
		var user = auth.authenticate(name);
		// Opens the wallet: 100 faíscas of genesis + 5 of presence = 105.000 mf.
		mockMvc.perform(get("/carteira").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balance").value(105_000));
		return user;
	}

	private long balance(TestAuthHelper.AuthSession user) throws Exception {
		MvcResult result = mockMvc.perform(get("/carteira").header("X-Session-Token", user.token()))
			.andExpect(status().isOk()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("balance").asLong();
	}

	private void buy(TestAuthHelper.AuthSession user, String itemId) throws Exception {
		mockMvc.perform(post("/loja/" + itemId + "/comprar").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(itemId))
			.andExpect(jsonPath("$.owned").value(true));
	}

	private JsonNode me(TestAuthHelper.AuthSession user) throws Exception {
		MvcResult result = mockMvc.perform(get("/me").header("X-Session-Token", user.token()))
			.andExpect(status().isOk()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private String json(Object value) throws Exception {
		return objectMapper.writeValueAsString(value);
	}

	@Test
	void catalogListsKindsCollectionsSeasonAndSaleFlags() throws Exception {
		mockMvc.perform(get("/loja"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(254))
			.andExpect(jsonPath("$[?(@.id == 'moldura-ambar')].collection").value(org.hamcrest.Matchers.contains(
				org.hamcrest.Matchers.nullValue())))
			.andExpect(jsonPath("$[?(@.id == 'moldura-ambar')].available").value(true))
			.andExpect(jsonPath("$[?(@.id == 'moldura-ambar')].kind").value("FRAME"))
			.andExpect(jsonPath("$[?(@.id == 'tema-saci')].kind").value("CARD_THEME"))
			.andExpect(jsonPath("$[?(@.id == 'tema-saci')].collection").value("saci"))
			.andExpect(jsonPath("$[?(@.id == 'tema-saci')].priceMf").value(4_000))
			.andExpect(jsonPath("$[?(@.id == 'tema-saci')].available").value(true))
			.andExpect(jsonPath("$[?(@.id == 'tema-saci')].purchasable").value(true))
			.andExpect(jsonPath("$[?(@.id == 'tema-saci')].cssRing").value(""))
			.andExpect(jsonPath("$[?(@.id == 'moldura-redemoinho')].priceMf").value(5_000))
			.andExpect(jsonPath("$[?(@.id == 'emblema-carapuca')].kind").value("BADGE"))
			.andExpect(jsonPath("$[?(@.id == 'nome-saci')].kind").value("NAME_STYLE"))
			.andExpect(jsonPath("$[?(@.id == 'placa-mata')].kind").value("NAMEPLATE"))
			.andExpect(jsonPath("$[?(@.id == 'banner-saci')].kind").value("BANNER"))
			.andExpect(jsonPath("$[?(@.id == 'efeito-redemoinho')].kind").value("PROFILE_EFFECT"))
			.andExpect(jsonPath("$[?(@.id == 'garden-cerrado')].kind").value("GARDEN"))
			.andExpect(jsonPath("$[?(@.id == 'garden-cerrado')].available").value(true))
			.andExpect(jsonPath("$[?(@.id == 'tema-criancas')].available").value(false))
			.andExpect(jsonPath("$[?(@.id == 'emblema-time-saci')].purchasable").value(false))
			.andExpect(jsonPath("$[?(@.id == 'emblema-time-saci')].collection").value("saci"))
			.andExpect(jsonPath("$[0].owned").doesNotExist())
			.andExpect(jsonPath("$[0].equipped").doesNotExist());
	}

	@Test
	void collectionsExposeWindowsAvailabilityAndLooks() throws Exception {
		mockMvc.perform(get("/loja/colecoes"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(11))
			.andExpect(jsonPath("$[?(@.id == 'saci')].name").value("Dia do Saci"))
			.andExpect(jsonPath("$[?(@.id == 'saci')].available").value(true))
			.andExpect(jsonPath("$[?(@.id == 'saci')].windows[0].start").value("10-15"))
			.andExpect(jsonPath("$[?(@.id == 'saci')].windows[0].end").value("11-02"))
			.andExpect(jsonPath("$[?(@.id == 'saci')].looks[0].id").value("look-saci"))
			.andExpect(jsonPath("$[?(@.id == 'saci')].looks[0].title").value("Saci-pererê"))
			.andExpect(jsonPath("$[?(@.id == 'saci')].looks[0].items[0]").value("tema-saci"))
			.andExpect(jsonPath("$[?(@.id == 'saci')].looks[0].statusIcon").value("carapuca"))
			.andExpect(jsonPath("$[?(@.id == 'saci')].looks[0].statusText").value("Procurando minha carapuça"))
			.andExpect(jsonPath("$[?(@.id == 'dev')].windows.length()").value(2))
			.andExpect(jsonPath("$[?(@.id == 'reveillon')].windows[0].start").value("12-26"))
			.andExpect(jsonPath("$[?(@.id == 'reveillon')].windows[0].end").value("01-06"))
			.andExpect(jsonPath("$[?(@.id == 'criancas')].available").value(false))
			// Dia dos Namorados has no banner: the look skips it.
			.andExpect(jsonPath("$[?(@.id == 'namorados')].looks[0].items.length()").value(7));
	}

	@Test
	void buyRespectsSeasonSaleFlagAndOwnership() throws Exception {
		var buyer = wallet("seasonal_buyer");
		buy(buyer, "tema-saci");
		assertThat(balance(buyer)).isEqualTo(101_000);

		mockMvc.perform(post("/loja/tema-saci/comprar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_OWNED"));
		mockMvc.perform(post("/loja/tema-criancas/comprar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OUT_OF_SEASON"));
		mockMvc.perform(post("/loja/emblema-time-saci/comprar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_FOR_SALE"));
		mockMvc.perform(post("/loja/nao-existe/comprar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));
		mockMvc.perform(post("/loja/tema-saci/comprar"))
			.andExpect(status().isUnauthorized());
		assertThat(balance(buyer)).isEqualTo(101_000);

		// Out of season the item stays listed but cannot be bought; owned items still equip. The session
		// would not survive the clock jump, so this part talks to the service directly.
		clock.set(java.time.Instant.parse("2026-11-03T15:00:00Z"));
		var row = cosmetics.shop(buyer.userId(), "oldest").stream()
			.filter(item -> item.item().id().equals("tema-saci")).findFirst().orElseThrow();
		assertThat(row.available()).isFalse();
		assertThat(row.owned()).isTrue();
		assertThatThrownBy(() -> cosmetics.buy(buyer.userId(), "moldura-carapuca"))
			.hasFieldOrPropertyWithValue("code", "OUT_OF_SEASON");
		assertThat(cosmetics.equip(buyer.userId(), "tema-saci").cardTheme()).isEqualTo("tema-saci");
	}

	@Test
	void buyingUntilTheWalletRunsOutStopsWithInsufficientSparks() throws Exception {
		var buyer = wallet("seasonal_broke");
		MvcResult shop = mockMvc.perform(get("/loja")).andExpect(status().isOk()).andReturn();
		int lastStatus = 200;
		for (JsonNode item : objectMapper.readTree(shop.getResponse().getContentAsString())) {
			if (!item.get("available").asBoolean() || !item.get("purchasable").asBoolean()) {
				continue;
			}
			MvcResult result = mockMvc.perform(post("/loja/" + item.get("id").asText() + "/comprar")
				.header("X-Session-Token", buyer.token())).andReturn();
			lastStatus = result.getResponse().getStatus();
			if (lastStatus != 200) {
				assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText())
					.isEqualTo("INSUFFICIENT_SPARKS");
				break;
			}
		}
		assertThat(lastStatus).isEqualTo(400);
		assertThat(balance(buyer)).isBetween(0L, 104_999L);
	}

	@Test
	void lookBuysMissingPiecesAtDiscountAndEquipsEverything() throws Exception {
		var buyer = wallet("seasonal_look");
		MvcResult bought = mockMvc.perform(post("/loja/looks/look-saci/comprar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value("look-saci"))
			.andExpect(jsonPath("$.bought.length()").value(11))
			// 31 faíscas of pieces, 75% = 23.25, rounded to 23.
			.andExpect(jsonPath("$.priceMf").value(23_000))
			.andReturn();
		assertThat(objectMapper.readTree(bought.getResponse().getContentAsString()).get("bought").get(0).asText())
			.isEqualTo("tema-saci");
		assertThat(balance(buyer)).isEqualTo(82_000);

		JsonNode me = me(buyer);
		assertThat(me.get("cardTheme").asText()).isEqualTo("tema-saci");
		assertThat(me.get("avatarFrame").asText()).isEqualTo("moldura-saci-completo");
		assertThat(me.get("nameTheme").asText()).isEqualTo("nome-saci");
		assertThat(me.get("profileEffect").asText()).isEqualTo("efeito-redemoinho");
		assertThat(me.get("bannerItem").asText()).isEqualTo("banner-saci");
		assertThat(me.get("nameplate").asText()).isEqualTo("placa-mata");
		assertThat(me.get("statusText").asText()).isEqualTo("Procurando minha carapuça");
		assertThat(me.get("statusIcon").asText()).isEqualTo("carapuca");
		assertThat(objectMapper.convertValue(me.get("badges"), List.class)).containsExactly("emblema-carapuca",
			"emblema-cachimbo", "emblema-redemoinho", "emblema-pe", "emblema-folhaSeca");

		// The public profile carries the same slots.
		String username = me.get("username").asText();
		mockMvc.perform(get("/users/" + username))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.cardTheme").value("tema-saci"))
			.andExpect(jsonPath("$.nameplate").value("placa-mata"))
			.andExpect(jsonPath("$.bannerItem").value("banner-saci"))
			.andExpect(jsonPath("$.profileEffect").value("efeito-redemoinho"))
			.andExpect(jsonPath("$.statusText").value("Procurando minha carapuça"))
			.andExpect(jsonPath("$.statusIcon").value("carapuca"))
			.andExpect(jsonPath("$.nameTheme").value("nome-saci"));

		mockMvc.perform(post("/loja/looks/look-saci/comprar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_OWNED"));
		assertThat(balance(buyer)).isEqualTo(82_000);
	}

	@Test
	void lookChargesOnlyMissingPiecesAndRespectsSeason() throws Exception {
		var buyer = wallet("seasonal_look_partial");
		buy(buyer, "tema-halloween");
		mockMvc.perform(post("/loja/looks/look-halloween/comprar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.bought.length()").value(10))
			// 27 faíscas still missing, 75% = 20.25, rounded to 20.
			.andExpect(jsonPath("$.priceMf").value(20_000));
		assertThat(balance(buyer)).isEqualTo(105_000 - 4_000 - 20_000);

		mockMvc.perform(post("/loja/looks/look-criancas/comprar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OUT_OF_SEASON"));
		mockMvc.perform(post("/loja/looks/look-nada/comprar").header("X-Session-Token", buyer.token()))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));
	}

	@Test
	void concurrentLookPurchasesChargeOnce() throws Exception {
		var buyer = wallet("seasonal_look_race");
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Callable<String>> calls = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				calls.add(() -> {
					try {
						return String.valueOf(cosmetics.buyLook(buyer.userId(), "look-saci").priceMf());
					}
					catch (RuntimeException e) {
						return e.getClass().getSimpleName();
					}
				});
			}
			List<String> outcomes = new ArrayList<>();
			for (Future<String> outcome : pool.invokeAll(calls)) {
				outcomes.add(outcome.get());
			}
			assertThat(outcomes).contains("23000");
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(balance(buyer)).isEqualTo(82_000);
	}

	@Test
	void everyKindEquipsAndUnequipsIntoItsSlot() throws Exception {
		var user = wallet("seasonal_slots");
		Map<String, String> slots = new HashMap<>();
		slots.put("moldura-carapuca", "avatarFrame");
		slots.put("nome-saci", "nameTheme");
		slots.put("placa-mata", "nameplate");
		slots.put("banner-saci", "bannerItem");
		slots.put("tema-saci", "cardTheme");
		slots.put("efeito-redemoinho", "profileEffect");
		slots.put("garden-cerrado", "gardenStyle");

		for (String item : slots.keySet()) {
			mockMvc.perform(post("/loja/" + item + "/equipar").header("X-Session-Token", user.token()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_OWNED"));
			mockMvc.perform(post("/loja/" + item + "/desequipar").header("X-Session-Token", user.token()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_OWNED"));
			buy(user, item);
			mockMvc.perform(post("/loja/" + item + "/equipar").header("X-Session-Token", user.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(item))
				.andExpect(jsonPath("$.equipped").value(true));
		}
		buy(user, "emblema-carapuca");
		mockMvc.perform(post("/loja/emblema-carapuca/equipar").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());

		JsonNode me = me(user);
		slots.forEach((item, field) -> assertThat(me.get(field).asText()).as(field).isEqualTo(item));
		// The public profile carries the garden style and the garden itself (a new account: seeds, healthy).
		mockMvc.perform(get("/users/" + me.get("username").asText()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.gardenStyle").value("garden-cerrado"))
			.andExpect(jsonPath("$.garden.tenure").value(0))
			.andExpect(jsonPath("$.garden.health").value(2));
		assertThat(me.get("badges").get(0).asText()).isEqualTo("emblema-carapuca");
		MvcResult shop = mockMvc.perform(get("/loja").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == 'emblema-carapuca')].equipped").value(true))
			.andExpect(jsonPath("$[?(@.id == 'emblema-carapuca')].owned").value(true))
			.andExpect(jsonPath("$[?(@.id == 'emblema-cachimbo')].owned").value(false))
			.andExpect(jsonPath("$[?(@.id == 'moldura-ambar')].equipped").value(false))
			.andReturn();
		for (JsonNode row : objectMapper.readTree(shop.getResponse().getContentAsString())) {
			if (slots.containsKey(row.get("id").asText())) {
				assertThat(row.get("equipped").asBoolean()).as(row.get("id").asText()).isTrue();
			}
		}

		for (String item : slots.keySet()) {
			mockMvc.perform(post("/loja/" + item + "/desequipar").header("X-Session-Token", user.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(item))
				.andExpect(jsonPath("$.equipped").value(false));
		}
		mockMvc.perform(post("/loja/emblema-carapuca/desequipar").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());
		JsonNode cleared = me(user);
		slots.values().forEach(field -> assertThat(cleared.get(field).isNull()).as(field).isTrue());
		assertThat(cleared.get("badges")).isEmpty();
	}

	@Test
	void sixthBadgeIsRefusedButReorderingAnEquippedOneIsNot() throws Exception {
		var user = wallet("seasonal_badges");
		mockMvc.perform(post("/loja/looks/look-saci/comprar").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());
		buy(user, "emblema-morcego");
		mockMvc.perform(post("/loja/emblema-morcego/equipar").header("X-Session-Token", user.token()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MAX_BADGES"));
		mockMvc.perform(post("/loja/emblema-pe/equipar").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());
		assertThat(me(user).get("badges").get(0).asText()).isEqualTo("emblema-pe");
		assertThat(me(user).get("badges")).hasSize(5);
	}

	@Test
	void officialBadgesCannotBeShownWithoutOwningThem() throws Exception {
		var user = wallet("seasonal_badge_forge");
		mockMvc.perform(put("/me/badges").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("badges", List.of("emblema-time-saci")))))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_OWNED"));
		buy(user, "emblema-cachimbo");
		mockMvc.perform(put("/me/badges").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("badges", List.of("emblema-cachimbo", "https://cdn.test/meu-emblema.png")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.badges[0]").value("emblema-cachimbo"));
	}

	@Test
	void statusAcceptsFreeAndOwnedIconsOnly() throws Exception {
		var user = wallet("seasonal_status");
		mockMvc.perform(put("/me/status").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("text", "  Tomando um café​  ", "icon", "cafe"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.statusText").value("Tomando um café"))
			.andExpect(jsonPath("$.statusIcon").value("cafe"));

		mockMvc.perform(put("/me/status").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("text", "Procurando", "icon", "carapuca"))))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STATUS_ICON"));
		mockMvc.perform(put("/me/status").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("text", "Oi", "icon", "url(evil)"))))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STATUS_ICON"));
		buy(user, "emblema-carapuca");
		mockMvc.perform(put("/me/status").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("text", "Procurando", "icon", "carapuca"))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.statusIcon").value("carapuca"));

		mockMvc.perform(put("/me/status").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("text", "x".repeat(61)))))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FIELD_TOO_LONG"));

		Map<String, Object> clear = new HashMap<>();
		clear.put("text", "");
		clear.put("icon", null);
		mockMvc.perform(put("/me/status").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON).content(json(clear)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.statusText").doesNotExist());
		JsonNode me = me(user);
		assertThat(me.get("statusText").isNull()).isTrue();
		assertThat(me.get("statusIcon").isNull()).isTrue();
	}

	@Test
	void equippingASeasonalNameStyleDropsTheFontAndColorPickedBefore() throws Exception {
		var user = wallet("seasonal_name_reset");
		mockMvc.perform(put("/cosmetics/profile/name-style").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("theme", "GOLD", "font", "SERIF", "color", "#123ABC"))))
			.andExpect(status().isOk());
		buy(user, "nome-saci");
		mockMvc.perform(post("/loja/nome-saci/equipar").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());

		JsonNode me = me(user);
		assertThat(me.get("nameTheme").asText()).isEqualTo("nome-saci");
		assertThat(me.get("nameFont").isNull()).isTrue();
		assertThat(me.get("nameColor").isNull()).isTrue();
	}

	@Test
	void nameStyleAcceptsFreeAndOwnedSeasonalThemesAndFollowsPostsAndComments() throws Exception {
		var user = wallet("seasonal_name_style");
		mockMvc.perform(put("/cosmetics/profile/name-style").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("theme", "GOLD", "font", "SERIF", "color", "#123ABC"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.theme").value("GOLD"))
			.andExpect(jsonPath("$.color").value("#123abc"));

		mockMvc.perform(put("/cosmetics/profile/name-style").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("theme", "nome-assombrado"))))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_OWNED"));

		buy(user, "nome-saci");
		mockMvc.perform(put("/cosmetics/profile/name-style").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("theme", "nome-saci"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.theme").value("nome-saci"))
			.andExpect(jsonPath("$.font").doesNotExist());

		MvcResult published = mockMvc.perform(post("/feed/posts").header("X-Session-Token", user.token())
				.header("Idempotency-Key", "seasonal-name-style-post")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("type", "INSIGHT", "content", "Post com nome do Saci", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorStyle.theme").value("nome-saci"))
			.andReturn();
		String postId = objectMapper.readTree(published.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/feed/posts/" + postId + "/comments").header("X-Session-Token", user.token())
				.header("Idempotency-Key", "seasonal-name-style-comment")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("content", "Comentario do Saci", "mediaIds", List.of(),
					"mentionedUserIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorStyle.theme").value("nome-saci"));
		mockMvc.perform(get("/feed/posts/" + postId + "/comments").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].authorStyle.theme").value("nome-saci"));
	}
}
