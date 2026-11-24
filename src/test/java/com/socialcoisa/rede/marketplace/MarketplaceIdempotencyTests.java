package com.socialcoisa.rede.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.marketplace.application.MarketplaceService;
import com.socialcoisa.rede.marketplace.application.port.out.MarketplaceRepository;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("critico")
class MarketplaceIdempotencyTests {
	@Autowired MockMvc http;
	@Autowired ObjectMapper json;
	@Autowired MarketplaceService marketplace;
	@Autowired MarketplaceRepository repository;
	@Autowired CoinService coins;

	@Test
	void repeatingABuyAfterRefundReplaysTheOriginalResponseWithoutChargingAgain() throws Exception {
		var buyer = new TestAuthHelper(http, json).authenticate("mkt_idem_replay");
		String marker = UUID.randomUUID().toString();
		coins.reward(buyer.userId(), CoinService.FAIS(200), "mkt-idem-replay-" + marker, "Saldo de teste");
		var listing = marketplace.publish("seller-" + marker, "THEME", "theme-" + marker,
			"Tema idempotente", null, MarketplaceService.MIN_PRICE);
		String route = "/marketplace/" + listing.id() + "/buy";
		String original = http.perform(post(route).header("X-Session-Token", buyer.token())
			.header("Idempotency-Key", "buy-original"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String purchase = json.readTree(original).get("purchaseId").asText();
		http.perform(post("/marketplace/purchases/" + purchase + "/refund")
			.header("X-Session-Token", buyer.token()).header("Idempotency-Key", "refund-original"))
			.andExpect(status().isOk());
		long refundedBalance = coins.walletSnapshot(buyer.userId(), "", 50).balance();
		String replay = http.perform(post(route).header("X-Session-Token", buyer.token())
			.header("Idempotency-Key", "buy-original"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(replay).isEqualTo(original);
		assertThat(repository.findPurchasesByBuyer(buyer.userId()))
			.filteredOn(current -> current.listingId().equals(listing.id())).hasSize(1);
		assertThat(coins.walletSnapshot(buyer.userId(), "", 50).balance()).isEqualTo(refundedBalance);
		http.perform(post("/marketplace/purchases/" + purchase + "/refund")
			.header("X-Session-Token", buyer.token()).header("Idempotency-Key", "refund-original"))
			.andExpect(status().isOk());
		assertThat(coins.walletSnapshot(buyer.userId(), "", 50).balance()).isEqualTo(refundedBalance);
		http.perform(post(route).header("X-Session-Token", buyer.token())
			.header("Idempotency-Key", "intentional-new-buy"))
			.andExpect(status().isOk());
		assertThat(repository.findPurchasesByBuyer(buyer.userId()))
			.filteredOn(current -> current.listingId().equals(listing.id())).hasSize(2);
	}

	@Test
	void purchaseDecisionsRequireAnIdempotencyKey() throws Exception {
		var buyer = new TestAuthHelper(http, json).authenticate("mkt_idem_required");
		http.perform(post("/marketplace/missing-listing/buy").header("X-Session-Token", buyer.token()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
		http.perform(post("/marketplace/purchases/missing-purchase/confirm").header("X-Session-Token", buyer.token()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
		http.perform(post("/marketplace/purchases/missing-purchase/refund").header("X-Session-Token", buyer.token()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
	}
}
