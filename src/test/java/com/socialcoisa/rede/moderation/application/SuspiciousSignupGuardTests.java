package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Um nome suspeito não cala uma rede inteira: a pausa acompanha o aparelho que repetiu, e a rede
 * só entra depois de várias ocorrências.
 */
@Tag("critico")
class SuspiciousSignupGuardTests {

	private static final String NETWORK = "198.51.100.7";

	private SuspiciousSignupGuard guard() {
		return new SuspiciousSignupGuard(true, "probe", 20_000);
	}

	private static void refuted(SuspiciousSignupGuard guard, String name, String email, String device) {
		assertThatThrownBy(() -> guard.checkSignup(name, email, NETWORK, device))
			.isInstanceOfSatisfying(ApiException.class, error -> {
				assertThat(error.status()).isEqualTo(403);
				assertThat(error.code()).isEqualTo("SIGNUP_REFUSED");
			});
	}

	@Test
	void oneSuspiciousAccountNeverPausesTheNetwork() {
		SuspiciousSignupGuard guard = guard();

		refuted(guard, "probe-x3mm", "a@b.test", "device-1");

		// Vizinho de rede, aparelho próprio: continua podendo se cadastrar.
		assertThatCode(() -> guard.checkSignup("Maria", "maria@example.test", NETWORK, "device-2"))
			.doesNotThrowAnyException();
	}

	@Test
	void theSameDeviceOnTheSameNetworkIsPausedAfterRepeats() {
		SuspiciousSignupGuard guard = guard();
		for (int i = 0; i < SuspiciousSignupGuard.PAIR_STRIKES; i++) {
			refuted(guard, "probe-" + i, "a" + i + "@b.test", "device-9");
		}

		// O aparelho em pausa não volta nem com nome limpo...
		refuted(guard, "Joana Lima", "joana@example.test", "device-9");
		// ...e o vizinho da mesma rede, em outro aparelho, segue livre.
		assertThatCode(() -> guard.checkSignup("Carlos", "carlos@example.test", NETWORK, "device-10"))
			.doesNotThrowAnyException();
	}

	@Test
	void theNetworkIsPausedOnlyAfterSeveralRepeats() {
		SuspiciousSignupGuard guard = guard();
		for (int i = 0; i < SuspiciousSignupGuard.IP_STRIKES; i++) {
			refuted(guard, "probe-" + i, "a" + i + "@b.test", "device-" + i);
		}

		// Aparelhos diferentes, mesma rede: agora a rede inteira está em pausa.
		refuted(guard, "Paulo", "paulo@example.test", "device-new");
	}

	@Test
	void aSuspiciousAccountWithoutDeviceDoesNotPauseTheNetwork() {
		SuspiciousSignupGuard guard = guard();
		for (String name : java.util.List.of("probe-1", "probe-2", "probe-3", "probe-4", "probe-5", "probe-6",
				"probe-7", "probe-8", "probe-9", "probe-10")) {
			assertThatThrownBy(() -> guard.checkSignup(name, "a@b.test", NETWORK)).isInstanceOf(ApiException.class);
		}

		assertThatCode(() -> guard.checkSignup("Ana", "ana@example.test", NETWORK, null))
			.doesNotThrowAnyException();
	}

	@Test
	void aCleanNameIsNeverTouched() {
		SuspiciousSignupGuard guard = guard();

		assertThatCode(() -> guard.checkSignup("Fernanda Souza", "fernanda@example.test", "203.0.113.4", "device-a"))
			.doesNotThrowAnyException();
	}

	@Test
	void theGuardCanBeDisabled() {
		SuspiciousSignupGuard guard = new SuspiciousSignupGuard(false, "probe", 20_000);

		assertThatCode(() -> guard.checkSignup("probe-x", "a@b.test", "203.0.113.5", "device-a"))
			.doesNotThrowAnyException();
	}
}
