package com.socialcoisa.rede.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UsernameSlugTests {

	@Test
	void accentsBecomeThePlainLetterInsteadOfAHyphen() {
		assertThat(UsernameSlug.from("Cecília Maria")).isEqualTo("cecilia-maria");
		assertThat(UsernameSlug.from("João Conceição")).isEqualTo("joao-conceicao");
		assertThat(UsernameSlug.from("Ângela Ünsal")).isEqualTo("angela-unsal");
	}

	@Test
	void symbolsBecomeOneHyphenAndNothingLeftFallsBackToUser() {
		assertThat(UsernameSlug.from("  Ana & Bia!! ")).isEqualTo("ana-bia");
		assertThat(UsernameSlug.from("ana.souza")).isEqualTo("ana-souza");
		assertThat(UsernameSlug.from("!!!")).isEqualTo("user");
		assertThat(UsernameSlug.from(null)).isEqualTo("user");
	}
}
