package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

@Tag("critico")
class TotpAndCipherTests {

	/** Segredo dos vetores do RFC 6238 ("12345678901234567890") em base32. */
	private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

	@Test
	void matchesTheRfc6238TestVectors() {
		// Vetores oficiais (SHA-1, 8 digitos): comparamos pelos 6 ultimos, que e o que usamos.
		assertThat(Totp.codeAt(RFC_SECRET, 59L / 30)).endsWith("94287082".substring(2));
		assertThat(Totp.codeAt(RFC_SECRET, 1111111109L / 30)).endsWith("07081804".substring(2));
		assertThat(Totp.codeAt(RFC_SECRET, 1111111111L / 30)).endsWith("14050471".substring(2));
		assertThat(Totp.codeAt(RFC_SECRET, 1234567890L / 30)).endsWith("89005924".substring(2));
		assertThat(Totp.codeAt(RFC_SECRET, 2000000000L / 30)).endsWith("69279037".substring(2));
		assertThat(Totp.codeAt(RFC_SECRET, 20000000000L / 30)).endsWith("65353130".substring(2));
	}

	@Test
	void acceptsTheCurrentStepAndTheNeighbouringOnesOnly() {
		Instant moment = Instant.ofEpochSecond(1_111_111_111L);
		String current = Totp.codeAt(RFC_SECRET, 1_111_111_111L / 30);

		assertThat(Totp.verify(RFC_SECRET, current, moment)).isTrue();
		// Um passo antes/depois continua valendo (relogio do celular fora de sincronia).
		assertThat(Totp.verify(RFC_SECRET, current, moment.plusSeconds(30))).isTrue();
		assertThat(Totp.verify(RFC_SECRET, current, moment.minusSeconds(30))).isTrue();
		// Dois passos ja nao valem.
		assertThat(Totp.verify(RFC_SECRET, current, moment.plusSeconds(90))).isFalse();
		assertThat(Totp.verify(RFC_SECRET, current, moment.minusSeconds(90))).isFalse();
	}

	@Test
	void rejectsMalformedCodes() {
		Instant moment = Instant.ofEpochSecond(1_111_111_111L);
		assertThat(Totp.verify(RFC_SECRET, null, moment)).isFalse();
		assertThat(Totp.verify(RFC_SECRET, "", moment)).isFalse();
		assertThat(Totp.verify(RFC_SECRET, "12345", moment)).isFalse();
		assertThat(Totp.verify(RFC_SECRET, "abcdef", moment)).isFalse();
		assertThat(Totp.verify(null, "123456", moment)).isFalse();
	}

	@Test
	void acceptsCodesTypedWithSpaces() {
		Instant moment = Instant.ofEpochSecond(1_111_111_111L);
		String current = Totp.codeAt(RFC_SECRET, 1_111_111_111L / 30);
		assertThat(Totp.verify(RFC_SECRET, current.substring(0, 3) + " " + current.substring(3), moment)).isTrue();
	}

	@Test
	void generatedSecretIsBase32AndRandom() {
		String first = Totp.generateSecret();
		String second = Totp.generateSecret();

		assertThat(first).hasSize(32).matches("[A-Z2-7]+");
		assertThat(second).isNotEqualTo(first);
		// O segredo gerado serve para calcular um codigo que ele mesmo aceita.
		Instant moment = Instant.ofEpochSecond(1_700_000_000L);
		assertThat(Totp.verify(first, Totp.codeAt(first, 1_700_000_000L / 30), moment)).isTrue();
	}

	@Test
	void otpauthUriCarriesTheParametersAuthenticatorsNeed() {
		String uri = Totp.otpauthUri("Énóis", "pessoa@example.com", RFC_SECRET);

		assertThat(uri).startsWith("otpauth://totp/");
		assertThat(uri).contains("secret=" + RFC_SECRET).contains("algorithm=SHA1")
			.contains("digits=6").contains("period=30").contains("issuer=");
	}

	@Test
	void cipherRoundTripsAndRefusesWithoutAKey() {
		SecretCipher cipher = new SecretCipher(SecretCipher.generateKeyBase64());
		String secret = Totp.generateSecret();

		String encrypted = cipher.encrypt(secret);
		assertThat(encrypted).isNotEqualTo(secret).doesNotContain(secret);
		assertThat(cipher.decrypt(encrypted)).isEqualTo(secret);

		// IV diferente a cada chamada: o mesmo segredo nao gera o mesmo texto cifrado.
		assertThat(cipher.encrypt(secret)).isNotEqualTo(encrypted);

		// Sem chave configurada o recurso fica indisponivel, com erro explicito.
		SecretCipher disabled = new SecretCipher("");
		assertThat(disabled.isEnabled()).isFalse();
		assertThatThrownBy(() -> disabled.encrypt(secret))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("segundo fator");
	}

	@Test
	void cipherRequiresAKeyWhenConfiguredAsRequired() {
		assertThatThrownBy(() -> new SecretCipher("", true))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("REDE_SECURITY_ENCRYPTION_KEY");
	}

	@Test
	void cipherRefusesAKeyOfTheWrongSize() {
		String shortKey = java.util.Base64.getEncoder().encodeToString("chave-curta".getBytes(StandardCharsets.UTF_8));
		assertThatThrownBy(() -> new SecretCipher(shortKey))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("32 bytes");
	}

	@Test
	void cipherCannotReadWithAnotherKey() {
		SecretCipher first = new SecretCipher(SecretCipher.generateKeyBase64());
		SecretCipher second = new SecretCipher(SecretCipher.generateKeyBase64());
		String encrypted = first.encrypt("segredo-do-totp");

		assertThatThrownBy(() -> second.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
	}
}
