package com.socialcoisa.rede.shared.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/** Cidade + UF: os dois ou nenhum, UF entre as 27, e a chave que ignora acento e caixa. */
class CityStateTests {

	@Test
	void sameCityWithOrWithoutAccentsHasTheSameKey() {
		assertThat(CityState.of("São Paulo", "sp").cityKey())
			.isEqualTo(CityState.of("  sao   PAULO ", "SP").cityKey())
			.isEqualTo("sao paulo");
	}

	@Test
	void keepsTheCityAsWrittenAndUppercasesTheState() {
		CityState place = CityState.of("  Belo   Horizonte ", " mg ");
		assertThat(place.city()).isEqualTo("Belo Horizonte");
		assertThat(place.state()).isEqualTo("MG");
	}

	@Test
	void bothBlankMeansNoPlace() {
		assertThat(CityState.of(null, null)).isNull();
		assertThat(CityState.of("", " ")).isNull();
	}

	@Test
	void refusesOnlyOneOfTheTwo() {
		assertThatThrownBy(() -> CityState.of("Recife", null)).isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "CITY_STATE_INCOMPLETE");
		assertThatThrownBy(() -> CityState.of("", "PE")).isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "CITY_STATE_INCOMPLETE");
	}

	@Test
	void refusesAStateThatIsNotOneOfThe27() {
		assertThat(CityState.STATES).hasSize(27);
		assertThatThrownBy(() -> CityState.of("Lisboa", "LX")).isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "INVALID_STATE");
		assertThatThrownBy(() -> CityState.requireState("Minas")).isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "INVALID_STATE");
	}

	@Test
	void refusesACityAboveTheColumnLimit() {
		assertThatThrownBy(() -> CityState.of("a".repeat(81), "MG")).isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "CITY_TOO_LONG");
	}
}
