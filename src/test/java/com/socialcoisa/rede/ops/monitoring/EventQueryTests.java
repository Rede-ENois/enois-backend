package com.socialcoisa.rede.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventQueryTests {

	@Test
	void emptyMatchesEverything() {
		assertThat(EventQuery.parse(null).matches("qualquer coisa")).isTrue();
		assertThat(EventQuery.parse("  ").matches("qualquer coisa")).isTrue();
	}

	@Test
	void termsAreAnded() {
		var query = EventQuery.parse("500 /feed");
		assertThat(query.matches("get /feed -> 500")).isTrue();
		assertThat(query.matches("get /feed -> 200")).isFalse();
		assertThat(query.matches("get /post -> 500")).isFalse();
	}

	@Test
	void minusNegates() {
		var query = EventQuery.parse("500 -health");
		assertThat(query.matches("get /feed -> 500")).isTrue();
		assertThat(query.matches("get /health -> 500")).isFalse();
	}

	@Test
	void orUnitesAlternatives() {
		var query = EventQuery.parse("feed OR post");
		assertThat(query.matches("get /feed -> 500")).isTrue();
		assertThat(query.matches("get /post -> 500")).isTrue();
		assertThat(query.matches("get /user -> 500")).isFalse();
	}

	@Test
	void quotesGroupExactPhrase() {
		var query = EventQuery.parse("\"pilha exata\"");
		assertThat(query.matches("com a pilha exata aqui")).isTrue();
		assertThat(query.matches("com a pilha outra aqui")).isFalse();
	}
}
