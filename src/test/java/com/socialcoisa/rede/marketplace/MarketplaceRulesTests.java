package com.socialcoisa.rede.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.marketplace.application.MarketplaceService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Regras do marketplace: preço mínimo de 50 faíscas, nome de quem publicou no catálogo e
 * migração idempotente dos temas da galeria para listagens.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MarketplaceRulesTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MarketplaceService marketplace;

	@Autowired
	private CoinService coins;

	@Autowired
	private com.socialcoisa.rede.marketplace.application.port.out.MarketplaceRepository repository;

	private String publishedTheme(TestAuthHelper.AuthSession owner, String name) throws Exception {
		// Paleta completa: a publicacao agora cobra o contrato que o front consome (muted,
		// onPrimary, text.date e o resto), senao a validacao recusa com 400 antes de listar.
		String packageJson = """
				{"palette":{"background":"#f5e6c8","surface":"#f0e2c4","muted":"#e8d9b8",
				"border":"#d8c9a8","primary":"#7c3aed","onPrimary":"#ffffff","soft":"#ede8f5",
				"text":{"strong":"#3a2a10","subtle":"#5a4a30","date":"#777777"},"radius":"8px"}}""";

		MvcResult result = mockMvc.perform(post("/temas/galeria")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", name,
					"description", "Tema de teste",
					"packageJson", packageJson,
					"modeDefault", "light"))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void priceFloorAndSellerNameInTheCatalog() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var owner = auth.authenticate("mkt_rules_owner");
		String themeId = publishedTheme(owner, "Tema do dono");

		// Abaixo do piso: recusado antes de qualquer listagem.
		mockMvc.perform(post("/marketplace")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"itemKind", "THEME",
					"itemId", themeId,
					"title", "Tema do dono",
					"price", CoinService.FAIS(49)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PRICE_TOO_LOW"));

		// No piso: listado.
		mockMvc.perform(post("/marketplace")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"itemKind", "THEME",
					"itemId", themeId,
					"title", "Tema do dono",
					"price", CoinService.FAIS(50)))))
			.andExpect(status().isOk());

		// O catálogo mostra quem publicou (nome e @).
		String profile = mockMvc.perform(get("/users/by-id/" + owner.userId())
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String expectedUsername = objectMapper.readTree(profile).get("username").asText();

		String catalog = mockMvc.perform(get("/marketplace"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		boolean found = false;
		for (var item : objectMapper.readTree(catalog)) {
			if (themeId.equals(item.get("itemId").asText())) {
				assertThat(item.get("sellerUsername").asText()).isEqualTo(expectedUsername);
				assertThat(item.get("sellerName").asText()).isEqualTo("mkt_rules_owner");
				found = true;
			}
		}
		assertThat(found).isTrue();
	}

	@Test
	void ensureListedIsIdempotentForTheGalleryMigration() throws Exception {
		String itemId = "theme_migracao_teste";

		assertThat(marketplace.ensureListed("user_migracao", "THEME", itemId, "Tema migrado", null,
			MarketplaceService.MIN_PRICE)).isPresent();
		assertThat(marketplace.ensureListed("user_migracao", "THEME", itemId, "Tema migrado", null,
			MarketplaceService.MIN_PRICE)).isEmpty();
	}

	@Test
	void heldPurchaseIsRecognizedWhenTheItemIsListedAgain() throws Exception {
		rejectRepeatPurchase("held", false);
	}

	@Test
	void completedPurchaseIsRecognizedWhenTheItemIsListedAgain() throws Exception {
		rejectRepeatPurchase("released", true);
	}

	private void rejectRepeatPurchase(String suffix, boolean confirm) throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var seller = auth.authenticate("mkt_owned_seller_" + suffix);
		var buyer = auth.authenticate("mkt_owned_buyer_" + suffix);
		String themeId = publishedTheme(seller, "Tema comprado " + suffix);
		var listing = marketplace.publish(seller.userId(), "THEME", themeId, "Tema comprado", null,
			MarketplaceService.MIN_PRICE);
		coins.reward(buyer.userId(), CoinService.FAIS(120), "mkt-owned-" + suffix, "Saldo de teste");
		var purchase = marketplace.buy(listing.id(), buyer.userId());
		// Registro anterior à correção: o anúncio recebia SOLD e era recriado pelo importador.
		repository.saveListing(listing.markSold(java.time.Instant.now()));
		if (confirm) {
			marketplace.confirm(purchase.id(), buyer.userId());
		}
		var relisted = marketplace.ensureListed(seller.userId(), "THEME", themeId, "Tema comprado", null,
			MarketplaceService.MIN_PRICE).orElseThrow();
		assertThat(relisted.id()).isNotEqualTo(listing.id());
		long balance = walletBalance(buyer.token());

		mockMvc.perform(post("/marketplace/" + relisted.id() + "/buy")
				.header("Idempotency-Key", java.util.UUID.randomUUID().toString())
				.header("X-Session-Token", buyer.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ITEM_ALREADY_OWNED"));

		assertThat(walletBalance(buyer.token())).isEqualTo(balance);
		assertThat(repository.findPurchasesByBuyer(buyer.userId())).hasSize(1);
	}

	@Test
	void refundedPurchaseDoesNotCountAsOwnership() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var seller = auth.authenticate("mkt_owned_refund_seller");
		var buyer = auth.authenticate("mkt_owned_refund_buyer");
		String themeId = publishedTheme(seller, "Tema devolvido");
		var listing = marketplace.publish(seller.userId(), "THEME", themeId, "Tema devolvido", null,
			MarketplaceService.MIN_PRICE);
		coins.reward(buyer.userId(), CoinService.FAIS(60), "mkt-owned-refund", "Saldo de teste");
		var purchase = marketplace.buy(listing.id(), buyer.userId());
		assertThat(repository.hasPurchaseForItem(buyer.userId(), "THEME", themeId)).isTrue();
		marketplace.refund(purchase.id(), buyer.userId());
		assertThat(repository.hasPurchaseForItem(buyer.userId(), "THEME", themeId)).isFalse();
		assertThat(repository.hasPurchaseForItem(seller.userId(), "THEME", themeId)).isFalse();
		assertThat(marketplace.catalog(100, "Tema devolvido", "THEME"))
			.extracting(com.socialcoisa.rede.marketplace.domain.MarketplaceListing::id).contains(listing.id());
		var boughtAgain = marketplace.buy(listing.id(), buyer.userId());
		assertThat(boughtAgain.id()).isNotEqualTo(purchase.id());
		assertThat(repository.hasPurchaseForItem(buyer.userId(), "THEME", themeId)).isTrue();
	}

	@Test
	void refundReopensALegacySoldListingImmediately() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var seller = auth.authenticate("mkt_legacy_refund_seller");
		var buyer = auth.authenticate("mkt_legacy_refund_buyer");
		String themeId = publishedTheme(seller, "Tema legado devolvido");
		var listing = marketplace.publish(seller.userId(), "THEME", themeId, "Tema legado devolvido", null,
			MarketplaceService.MIN_PRICE);
		coins.reward(buyer.userId(), CoinService.FAIS(60), "mkt-legacy-refund", "Saldo de teste");
		var purchase = marketplace.buy(listing.id(), buyer.userId());
		repository.saveListing(listing.markSold(java.time.Instant.now()));
		marketplace.refund(purchase.id(), buyer.userId());
		assertThat(repository.findListingById(listing.id()).orElseThrow().status())
			.isEqualTo(com.socialcoisa.rede.marketplace.domain.MarketplaceListing.Status.ACTIVE);
		assertThat(marketplace.buy(listing.id(), buyer.userId()).id()).isNotEqualTo(purchase.id());
	}

	@Test
	void unconfirmedPurchaseReleasesOnceAfterTheDecisionWindow() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var seller = auth.authenticate("mkt_expiry_seller");
		var buyer = auth.authenticate("mkt_expiry_buyer");
		String themeId = publishedTheme(seller, "Tema sem confirmacao");
		var listing = marketplace.publish(seller.userId(), "THEME", themeId, "Tema sem confirmacao", null,
			MarketplaceService.MIN_PRICE);
		coins.reward(buyer.userId(), CoinService.FAIS(60), "mkt-expiry-balance", "Saldo de teste");
		var purchase = marketplace.buy(listing.id(), buyer.userId());
		long buyerBalance = walletBalance(buyer.token());
		long sellerBalance = walletBalance(seller.token());
		assertThat(buyerBalance).isLessThan(CoinService.FAIS(165));
		repository.savePurchase(new com.socialcoisa.rede.marketplace.domain.MarketplacePurchase(
			purchase.id(), purchase.listingId(), purchase.buyerId(), purchase.sellerId(), purchase.price(),
			purchase.state(), java.time.Instant.now().minus(java.time.Duration.ofDays(8)), null));

		marketplace.releaseExpired();
		assertThat(repository.findPurchaseById(purchase.id()).orElseThrow().state())
			.isEqualTo(com.socialcoisa.rede.marketplace.domain.MarketplacePurchase.State.RELEASED);
		assertThat(walletBalance(buyer.token())).isEqualTo(buyerBalance);
		assertThat(walletBalance(seller.token())).isEqualTo(sellerBalance + CoinService.FAIS(45));
		marketplace.releaseExpired();
		assertThat(walletBalance(seller.token())).isEqualTo(sellerBalance + CoinService.FAIS(45));
		mockMvc.perform(post("/marketplace/purchases/" + purchase.id() + "/refund")
				.header("Idempotency-Key", java.util.UUID.randomUUID().toString())
				.header("X-Session-Token", buyer.token()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_NOT_HELD"));
	}

	private long walletBalance(String token) throws Exception {
		var result = mockMvc.perform(get("/carteira").header("X-Session-Token", token))
			.andExpect(status().isOk()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("balance").asLong();
	}
}
