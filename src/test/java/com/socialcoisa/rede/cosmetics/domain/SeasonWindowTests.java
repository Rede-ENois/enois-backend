package com.socialcoisa.rede.cosmetics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.MonthDay;

import org.junit.jupiter.api.Test;

class SeasonWindowTests {

	@Test
	void plainWindowIncludesBothEnds() {
		SeasonWindow saci = SeasonWindow.parse("10-15", "11-02");
		assertThat(saci.contains(MonthDay.of(10, 15))).isTrue();
		assertThat(saci.contains(MonthDay.of(10, 31))).isTrue();
		assertThat(saci.contains(MonthDay.of(11, 2))).isTrue();
		assertThat(saci.contains(MonthDay.of(10, 14))).isFalse();
		assertThat(saci.contains(MonthDay.of(11, 3))).isFalse();
		assertThat(saci.contains(MonthDay.of(3, 1))).isFalse();
	}

	@Test
	void windowThatWrapsTheYearCoversDecemberAndJanuary() {
		SeasonWindow reveillon = SeasonWindow.parse("12-26", "01-06");
		assertThat(reveillon.contains(MonthDay.of(12, 26))).isTrue();
		assertThat(reveillon.contains(MonthDay.of(12, 31))).isTrue();
		assertThat(reveillon.contains(MonthDay.of(1, 1))).isTrue();
		assertThat(reveillon.contains(MonthDay.of(1, 6))).isTrue();
		assertThat(reveillon.contains(MonthDay.of(1, 7))).isFalse();
		assertThat(reveillon.contains(MonthDay.of(12, 25))).isFalse();
		assertThat(reveillon.contains(MonthDay.of(7, 1))).isFalse();
	}

	@Test
	void singleDayWindowAndTextRoundTrip() {
		SeasonWindow day = SeasonWindow.parse("02-29", "02-29");
		assertThat(day.contains(MonthDay.of(2, 29))).isTrue();
		assertThat(day.contains(MonthDay.of(3, 1))).isFalse();
		assertThat(SeasonWindow.parse("05-20", "05-31").startText()).isEqualTo("05-20");
		assertThat(SeasonWindow.parse("05-20", "05-31").endText()).isEqualTo("05-31");
	}

	@Test
	void invalidMonthDayFails() {
		assertThatThrownBy(() -> SeasonWindow.parse("13-01", "01-02")).isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> SeasonWindow.parse("10-15", null)).isInstanceOf(RuntimeException.class);
	}
}
