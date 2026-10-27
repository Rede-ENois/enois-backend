package com.socialcoisa.rede.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/** Failed logins slow down only the network that produced them, never the account owner elsewhere. */
class AuthThrottleKeyTests {

	@Test
	void failuresFromOneIpDoNotDelayAnotherIp() {
		AuthThrottle throttle = new AuthThrottle(1, 10);
		for (int i = 0; i < 3; i++) {
			throttle.registraFalhaLogin("victim@test.com", "198.51.100.1");
		}

		long start = System.nanoTime();
		throttle.exigeLoginPermitido("victim@test.com", "203.0.113.7");
		long otherIpMillis = (System.nanoTime() - start) / 1_000_000;

		assertThat(otherIpMillis).isLessThan(100);
		assertThatThrownBy(() -> throttle.exigeLoginPermitido("victim@test.com", "198.51.100.1"))
			.isInstanceOfSatisfying(ApiException.class, error -> {
				assertThat(error.code()).isEqualTo("LOGIN_THROTTLED");
				assertThat(error.status()).isEqualTo(429);
			});
	}

	@Test
	void sprayingOneAccountRefusesTheSprayingNetworksButNeverTheOwner() {
		AuthThrottle throttle = new AuthThrottle(5, 10);
		// 19 failures, each from a different network: under the account-wide threshold (4 x 5).
		for (int i = 0; i < 19; i++) {
			throttle.registraFalhaLogin("sprayed@test.com", "198.51.100." + i);
		}
		assertThat(throttle.accountThrottled("sprayed@test.com", "198.51.100.1")).isFalse();

		throttle.registraFalhaLogin("sprayed@test.com", "198.51.100.19");
		// A network that took part is refused at once (429, no sleeping thread)...
		assertThat(throttle.accountThrottled("sprayed@test.com", "198.51.100.1")).isTrue();
		assertThatThrownBy(() -> throttle.exigeLoginPermitido("sprayed@test.com", "198.51.100.1"))
			.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("LOGIN_THROTTLED"));
		// ...the owner on a clean network is not, and neither is another account.
		assertThat(throttle.accountThrottled("sprayed@test.com", "203.0.113.7")).isFalse();
		assertThat(throttle.loginDelayMillis("sprayed@test.com", "203.0.113.7")).isZero();
		assertThat(throttle.accountThrottled("other@test.com", "198.51.100.1")).isFalse();

		throttle.limpaLogin("sprayed@test.com", "203.0.113.7");
		assertThat(throttle.accountThrottled("sprayed@test.com", "198.51.100.1")).isFalse();
	}

	@Test
	void aFloodOfNewKeysNeverResetsTheAttackedAccount() {
		AuthThrottle throttle = new AuthThrottle(5, 10, 100);
		for (int i = 0; i < 5; i++) {
			throttle.registraFalhaLogin("target@test.com", "198.51.100.1");
		}
		for (int i = 0; i < 1_000; i++) {
			throttle.registraFalhaLogin("flood" + i + "@test.com", "203.0.113." + (i % 250));
		}
		assertThat(throttle.loginDelayMillis("target@test.com", "198.51.100.1")).isEqualTo(1_000L);
	}

	@Test
	void accountAndIpDelayStillAppliesOnItsOwn() {
		AuthThrottle throttle = new AuthThrottle(5, 10);
		for (int i = 0; i < 5; i++) {
			throttle.registraFalhaLogin("target@test.com", "198.51.100.1");
		}
		assertThat(throttle.loginDelayMillis("target@test.com", "198.51.100.1")).isEqualTo(1_000L);
		assertThat(throttle.loginDelayMillis("target@test.com", "203.0.113.7")).isZero();
	}
}
