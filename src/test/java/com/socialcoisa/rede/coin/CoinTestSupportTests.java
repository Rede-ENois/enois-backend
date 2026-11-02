package com.socialcoisa.rede.coin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

class CoinTestSupportTests {

	private static ApiException concurrency() {
		return new ApiException(409, "COIN_CONCURRENCY", "Movimentação concorrente; tente de novo.");
	}

	@Test
	void retriesTheConcurrencyConflictAndReturnsTheFirstSuccess() {
		var calls = new AtomicInteger();

		String result = CoinTestSupport.retryOnCoinConcurrency(() -> {
			if (calls.incrementAndGet() < 3) {
				throw concurrency();
			}
			return "ok";
		});

		assertThat(result).isEqualTo("ok");
		assertThat(calls).hasValue(3);
	}

	@Test
	void anyOtherErrorRisesImmediatelyWithoutRetry() {
		var calls = new AtomicInteger();

		assertThatThrownBy(() -> CoinTestSupport.retryOnCoinConcurrency(() -> {
			calls.incrementAndGet();
			throw new ApiException(400, "INSUFFICIENT_SPARKS", "Faíscas insuficientes.");
		})).isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "INSUFFICIENT_SPARKS");

		assertThat(calls).hasValue(1);
	}

	@Test
	void givesUpLoudlyWhenTheConflictNeverClears() {
		var calls = new AtomicInteger();

		assertThatThrownBy(() -> CoinTestSupport.retryOnCoinConcurrency(() -> {
			calls.incrementAndGet();
			throw concurrency();
		})).isInstanceOf(AssertionError.class).hasMessageContaining("COIN_CONCURRENCY persistiu")
			.hasCauseInstanceOf(ApiException.class);

		assertThat(calls).hasValue(CoinTestSupport.MAX_ATTEMPTS);
	}
}
