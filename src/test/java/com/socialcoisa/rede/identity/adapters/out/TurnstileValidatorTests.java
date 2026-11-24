package com.socialcoisa.rede.identity.adapters.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

@Tag("critico")
class TurnstileValidatorTests {

	private static final String TEST_SECRET = "1x0000000000000000000000000000000AA";
	private static final String TEST_SITE = "1x00000000000000000000AA";
	private static final String REAL_SECRET = "0x4AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	private static final String REAL_SITE = "0x4AAAAAAAAAAAAAAA";

	private static TurnstileValidator validator(String secret, String site, String policy) {
		return new TurnstileValidator(secret, site, policy, JsonMapper.builder().build());
	}

	/**
	 * Chave de teste aceita QUALQUER token no siteverify, entao nunca pode contar como captcha
	 * ativo — nem com a politica em degrade, que e a que a QA usa.
	 */
	@Test
	void testKeysNeverCountAsConfigured() {
		TurnstileValidator degraded = validator(TEST_SECRET, TEST_SITE, "degrade");

		assertThat(degraded.isConfigured()).isFalse();
		assertThat(degraded.isAvailable()).isFalse();
		// Inerte por desenho: quem decide o cadastro passa a ser o desafio proprio do backend.
		assertThatCode(() -> degraded.require("")).doesNotThrowAnyException();
	}

	/** O default (e o que vale em producao hoje): o site sobe, o captcha externo fica desligado. */
	@Test
	void degradePolicyKeepsTheSiteUp() {
		assertThatCode(() -> validator(TEST_SECRET, TEST_SITE, "degrade")).doesNotThrowAnyException();
	}

	/** A garantia dura continua disponivel para quem a quer: `fatal` recusa o boot. */
	@Test
	void fatalPolicyRefusesTestKeysAtBoot() {
		assertThatThrownBy(() -> validator(TEST_SECRET, TEST_SITE, "fatal"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("test keys");
	}

	/** Um typo na configuracao nao pode derrubar um ambiente: cai em degrade e avisa no log. */
	@Test
	void unknownPolicyFallsBackToDegrade() {
		assertThatCode(() -> validator(TEST_SECRET, TEST_SITE, "fata1")).doesNotThrowAnyException();
		assertThat(validator(TEST_SECRET, TEST_SITE, "fata1").isConfigured()).isFalse();
	}

	@Test
	void realKeysStayConfiguredAndAvailable() {
		TurnstileValidator prod = validator(REAL_SECRET, REAL_SITE, "fatal");

		assertThat(prod.isConfigured()).isTrue();
		assertThat(prod.isAvailable()).isTrue();
	}

	@Test
	void emptyKeysAreNotConfigured() {
		TurnstileValidator dev = validator("", "", "fatal");

		assertThat(dev.isConfigured()).isFalse();
		assertThat(dev.isAvailable()).isFalse();
	}
}
