package com.socialcoisa.rede.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What leaves the host (panel file, Telegram channel) never carries credentials or personal data. */
class SensitiveDataRedactorTests {

	@Test
	void removesCredentials() {
		String clean = SensitiveDataRedactor.redact("Authorization: Bearer abc.def-123456 x-session-token=tok_live_1 "
			+ "api_key=k-1 refresh_token: r-2 senha=minha jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig "
			+ "bot 123456789:AAHfakefakefakefakefakefakefakefake1 aws AKIAABCDEFGHIJKLMNOP "
			+ "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----");

		assertThat(clean).doesNotContain("abc.def-123456", "tok_live_1", "k-1", "r-2", "minha", "eyJhbGci",
			"AAHfakefake", "AKIAABCDEFGHIJKLMNOP", "MIIEow");
		assertThat(clean).contains("Bearer ***", "senha=***", "[private key removed]");
	}

	@Test
	void removesPersonalData() {
		String clean = SensitiveDataRedactor.redactLine("mail user@example.com card 4111-1111-1111-1111 cpf "
			+ "123.456.789-09 cnpj 12.345.678/0001-95 phone (31) 99876-5432 ip 198.51.100.23 /reset?code=998877&x=1");

		assertThat(clean).doesNotContain("user@example.com", "4111-1111", "123.456.789-09", "12.345.678/0001-95",
			"99876-5432", "198.51.100.23", "998877");
		assertThat(clean).contains("/reset?code=***&x=***");
	}

	@Test
	void keepsUsefulDiagnosticsReadable() {
		String text = "timeout after 3000 ms at 1730822400000 for order 42 status=500 route /feed/{id}";
		assertThat(SensitiveDataRedactor.redactLine(text)).isEqualTo(text);
	}

	@Test
	void redactLineFlattensButRedactKeepsLines() {
		assertThat(SensitiveDataRedactor.redactLine("a\nb\tc")).isEqualTo("a b c");
		assertThat(SensitiveDataRedactor.redact("a\nb")).isEqualTo("a\nb");
	}
}
