package com.socialcoisa.rede.calendar.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.security.Hashes;

/**
 * Regras do token da assinatura (fase 9, D7): o token nasce aleatório, é guardado só como hash,
 * regenerar troca a credencial sem criar linha e revogar é idempotente.
 */
class CalendarSubscriptionTests {

	private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

	private CalendarSubscription subscriptionOf(String token) {
		return CalendarSubscription.create("sub_" + token, "user_1", CalendarScope.SOCIAL, Hashes.sha256Hex(token),
			NOW);
	}

	@Test
	void newTokenIsLongRandomAndUrlSafe() {
		String first = CalendarSubscription.newToken();
		String second = CalendarSubscription.newToken();

		assertNotEquals(first, second);
		assertEquals(43, first.length());
		assertTrue(first.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_'));
	}

	@Test
	void matchesOnlyTheTokenThatProducedTheHash() {
		CalendarSubscription subscription = subscriptionOf("token-correto");

		assertTrue(subscription.matches("token-correto"));
		assertFalse(subscription.matches("token-errado"));
		assertFalse(subscription.matches(""));
		assertFalse(subscription.matches(null));
	}

	@Test
	void startsActiveWithoutRevocationDate() {
		CalendarSubscription subscription = subscriptionOf("token");

		assertTrue(subscription.active());
		assertNull(subscription.revokedAt());
	}

	@Test
	void revokingIsIdempotentAndKeepsTheFirstInstant() {
		CalendarSubscription subscription = subscriptionOf("token");

		subscription.revoke(NOW);
		subscription.revoke(NOW.plusSeconds(60));

		assertFalse(subscription.active());
		assertEquals(NOW, subscription.revokedAt());
	}

	@Test
	void consolidatedScopeIsTheNullScopeOfTheReadingContract() {
		CalendarSubscription consolidated = CalendarSubscription.create("sub_all", "user_1", null,
			Hashes.sha256Hex("token"), NOW);

		assertNull(consolidated.scope());
	}

	@Test
	void refusesAHashThatIsNotSha256Hex() {
		assertThrows(IllegalArgumentException.class,
			() -> CalendarSubscription.create("sub", "user_1", CalendarScope.SOCIAL, "nao-e-hash", NOW));
		assertThrows(IllegalArgumentException.class,
			() -> CalendarSubscription.create("sub", "user_1", CalendarScope.SOCIAL, "Z".repeat(64), NOW));
	}
}