package com.socialcoisa.rede.coin.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Who may receive automatic faíscas that a second account could farm (accepted answers, daily game
 * prizes): an account that is past its first week. The same window caps what a new account may send.
 */
public final class RewardEligibility {

	public static final Duration NEW_ACCOUNT_WINDOW = Duration.ofDays(7);

	private RewardEligibility() {
	}

	/** True when the account existed for the whole new-account window before {@code asOf}. */
	public static boolean isEstablished(Instant createdAt, Instant asOf) {
		return createdAt != null && !createdAt.plus(NEW_ACCOUNT_WINDOW).isAfter(asOf);
	}
}
