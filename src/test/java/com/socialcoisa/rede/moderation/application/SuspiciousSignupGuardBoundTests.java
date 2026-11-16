package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/** The per-subject strike and cooldown maps stay bounded however many distinct origins show up. */
class SuspiciousSignupGuardBoundTests {

	@Test
	void manyDistinctOriginsInCooldownDoNotGrowTheMapsPastTheCeiling() {
		SuspiciousSignupGuard guard = new SuspiciousSignupGuard(true, "probe", 10);
		for (int origin = 0; origin < 100; origin++) {
			String address = "198.51.100." + origin;
			String device = "device-" + origin;
			for (int strike = 0; strike < 3; strike++) {
				assertThatThrownBy(() -> guard.checkSignup("probe-x", "a@b.test", address, device))
					.isInstanceOf(ApiException.class);
			}
			assertThat(guard.trackedKeys()).isLessThanOrEqualTo(10);
		}
	}
}