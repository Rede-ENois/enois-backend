package com.socialcoisa.rede.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.coin.application.port.out.CoinLedgerRepository;
import com.socialcoisa.rede.marketplace.application.MarketplaceService;
import com.socialcoisa.rede.marketplace.application.port.out.MarketplaceRepository;
import com.socialcoisa.rede.marketplace.domain.MarketplaceListing;
import com.socialcoisa.rede.marketplace.domain.MarketplacePurchase;
import org.junit.jupiter.api.Tag;
import com.socialcoisa.rede.shared.web.ApiException;

@Tag("critico")
@SpringBootTest(properties = {
	"rede.marketplace.sanitize-initial-delay-ms=3600000",
	"rede.marketplace.gallery-import-initial-delay-ms=3600000"
})
class MarketplaceConcurrencyTests {
	@Autowired MarketplaceService marketplace;
	@Autowired CoinService coins;
	@Autowired CoinLedgerRepository ledger;
	@MockitoSpyBean MarketplaceRepository repository;

	@Test
	void simultaneousBuysChargeTheSameBuyerOnce() throws Exception {
		var listing = listing();
		String buyer = fundedBuyer();
		long before = balance(buyer);
		List<String> outcomes = race(8, () -> {
			try {
				marketplace.buy(listing.id(), buyer);
				return "BOUGHT";
			} catch (ApiException rejected) {
				return rejected.code();
			}
		});
		assertThat(outcomes).containsOnly("BOUGHT", "ITEM_ALREADY_OWNED");
		assertThat(outcomes.stream().filter("BOUGHT"::equals)).hasSize(1);
		assertThat(repository.findPurchasesByBuyer(buyer)).hasSize(1);
		assertThat(balance(buyer)).isEqualTo(before - listing.price());
		assertThat(ledger.balanceFromLedger(buyer)).isEqualTo(balance(buyer));
	}

	@Test
	void confirmationAndRefundHaveOneFinancialOutcome() throws Exception {
		for (int round = 0; round < 8; round++) {
			var listing = listing();
			String buyer = fundedBuyer();
			var purchase = marketplace.buy(listing.id(), buyer);
			long buyerBefore = balance(buyer);
			long sellerBefore = balance(listing.sellerId());
			var calls = List.<Callable<String>>of(
				() -> decide(() -> marketplace.confirm(purchase.id(), buyer)),
				() -> decide(() -> marketplace.refund(purchase.id(), buyer)));
			var outcomes = race(calls);
			assertThat(outcomes.stream().filter("OK"::equals)).hasSize(1);
			assertThat(outcomes).contains("PURCHASE_NOT_HELD");
			var state = repository.findPurchaseById(purchase.id()).orElseThrow().state();
			assertThat(state).isIn(MarketplacePurchase.State.RELEASED, MarketplacePurchase.State.REFUNDED);
			assertThat(balance(buyer)).isEqualTo(buyerBefore
				+ (state == MarketplacePurchase.State.REFUNDED ? purchase.price() : 0));
			assertThat(balance(listing.sellerId())).isEqualTo(sellerBefore
				+ (state == MarketplacePurchase.State.RELEASED ? purchase.price() * 90 / 100 : 0));
			assertThat(ledger.balanceFromLedger(buyer)).isEqualTo(balance(buyer));
			assertThat(ledger.balanceFromLedger(listing.sellerId())).isEqualTo(balance(listing.sellerId()));
		}
	}

	@Test
	void competingExpiryWorkersReleaseEachPurchaseOnce() throws Exception {
		var listing = listing();
		String buyer = fundedBuyer();
		var purchase = expired(marketplace.buy(listing.id(), buyer));
		long sellerBefore = balance(listing.sellerId());
		long buyerBefore = balance(buyer);
		race(4, () -> { marketplace.releaseExpired(); return "OK"; });
		assertThat(repository.findPurchaseById(purchase.id()).orElseThrow().state())
			.isEqualTo(MarketplacePurchase.State.RELEASED);
		assertThat(balance(listing.sellerId())).isEqualTo(sellerBefore + purchase.price() * 90 / 100);
		assertThat(balance(buyer)).isEqualTo(buyerBefore);
		assertThat(ledger.balanceFromLedger(listing.sellerId())).isEqualTo(balance(listing.sellerId()));
	}

	@Test
	void aFailedPersistenceWriteRollsBackTheRefundAndAllowsASafeRetry() {
		var listing = listing();
		String buyer = fundedBuyer();
		var purchase = marketplace.buy(listing.id(), buyer);
		long before = balance(buyer);
		var failOnce = new AtomicBoolean(true);
		doAnswer(invocation -> {
			if (failOnce.getAndSet(false)) throw new IllegalStateException("Falha de persistência de teste");
			return invocation.callRealMethod();
		}).when(repository).savePurchase(argThat(current -> current.id().equals(purchase.id())
			&& current.state() == MarketplacePurchase.State.REFUNDED));
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> marketplace.refund(purchase.id(), buyer))
			.isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
			.hasRootCauseInstanceOf(IllegalStateException.class);
		assertThat(balance(buyer)).isEqualTo(before);
		assertThat(ledger.balanceFromLedger(buyer)).isEqualTo(before);
		assertThat(repository.findPurchaseById(purchase.id()).orElseThrow().state())
			.isEqualTo(MarketplacePurchase.State.HELD);
		marketplace.refund(purchase.id(), buyer);
		assertThat(balance(buyer)).isEqualTo(before + purchase.price());
		assertThat(ledger.balanceFromLedger(buyer)).isEqualTo(balance(buyer));
	}

	private MarketplaceListing listing() {
		String marker = UUID.randomUUID().toString();
		return marketplace.publish("seller-" + marker, "THEME", "item-" + marker,
			"Tema concorrente", null, MarketplaceService.MIN_PRICE);
	}

	private String fundedBuyer() {
		String buyer = "buyer-" + UUID.randomUUID();
		coins.reward(buyer, CoinService.FAIS(200), "balance-" + buyer, "Saldo de teste");
		return buyer;
	}

	private long balance(String id) {
		return coins.walletSnapshot(id, id, 50).balance();
	}

	private MarketplacePurchase expired(MarketplacePurchase purchase) {
		return repository.savePurchase(new MarketplacePurchase(purchase.id(), purchase.listingId(),
			purchase.buyerId(), purchase.sellerId(), purchase.price(), purchase.state(),
			Instant.now().minus(Duration.ofDays(8)), null));
	}

	private String decide(Callable<MarketplacePurchase> action) throws Exception {
		try {
			action.call();
			return "OK";
		} catch (ApiException rejected) {
			return rejected.code();
		}
	}

	private List<String> race(int count, Callable<String> action) throws Exception {
		return race(java.util.Collections.nCopies(count, action));
	}

	private List<String> race(List<Callable<String>> actions) throws Exception {
		var gate = new CyclicBarrier(actions.size());
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var futures = actions.stream().map(action -> executor.submit(() -> {
				gate.await(10, TimeUnit.SECONDS);
				return action.call();
			})).toList();
			List<String> outcomes = new ArrayList<>();
			for (var future : futures) outcomes.add(future.get(20, TimeUnit.SECONDS));
			return outcomes;
		}
	}
}
