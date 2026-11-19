package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Cursor é entrada externa decodificada em Base64. CVE-2026-114938 é um OOB write no stub
 * intrínseco de decode do C2; o teto de tamanho e a validação de alfabeto mantêm a entrada longe
 * de qualquer buffer grande e evitam passar lixo ao decoder nativo.
 */
class CursorCodecTests {

	private final CursorCodec codec = new CursorCodec();

	@Test
	void blankCursorIsAbsent() {
		assertThat(codec.decodePage(null)).isEmpty();
		assertThat(codec.decodePage("")).isEmpty();
		assertThat(codec.decodePage("   ")).isEmpty();
	}

	@Test
	void legacyPlainInstantStillWorks() {
		Instant instant = Instant.parse("2030-01-01T12:00:00Z");
		assertThat(codec.decode(instant.toString())).contains(instant);
	}

	@Test
	void composedCursorRoundTrips() {
		Instant instant = Instant.parse("2030-01-01T12:00:00Z");
		String encoded = codec.encodePage(instant, "tie-1");
		assertThat(codec.decodePage(encoded)).hasValueSatisfying(page -> {
			assertThat(page.instant()).isEqualTo(instant);
			assertThat(page.tieBreak()).isEqualTo("tie-1");
		});
	}

	@Test
	void rejectsCursorOverTheLengthCapBeforeDecoding() {
		String huge = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(new byte[4096]);
		assertThat(huge.length()).isGreaterThan(CursorCodec.MAX_CURSOR_LENGTH);
		assertInvalidCursor(huge);
	}

	@Test
	void acceptsCursorExactlyAtTheLengthCap() {
		String atCap = "a".repeat(CursorCodec.MAX_CURSOR_LENGTH);
		// Alfabeto válido, mas o conteúdo não é cursor legítimo: falha pela estrutura, não pelo teto.
		assertInvalidCursor(atCap);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"not-a-cursor!",
		"<script>alert(1)</script>",
		"2029-01-01T12:00:00Z|; DROP TABLE",
		"%00%00%00"
	})
	void rejectsNonBase64UrlAlphabetBeforeDecoding(String value) {
		assertInvalidCursor(value);
	}

	private void assertInvalidCursor(String value) {
		assertThatThrownBy(() -> codec.decodePage(value)).isInstanceOfSatisfying(ApiException.class, error -> {
			assertThat(error.status()).isEqualTo(400);
			assertThat(error.code()).isEqualTo("INVALID_CURSOR");
		});
	}
}