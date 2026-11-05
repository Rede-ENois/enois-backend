package com.socialcoisa.rede.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReputationGardenTests {

	@Test
	void stageFollowsTheFractionOfTheCap() {
		assertThat(ReputationGarden.stage(0, 100)).isZero();
		assertThat(ReputationGarden.stage(9.99, 100)).isZero();
		assertThat(ReputationGarden.stage(10, 100)).isEqualTo(1);
		assertThat(ReputationGarden.stage(34.99, 100)).isEqualTo(1);
		assertThat(ReputationGarden.stage(35, 100)).isEqualTo(2);
		assertThat(ReputationGarden.stage(69.99, 100)).isEqualTo(2);
		assertThat(ReputationGarden.stage(70, 100)).isEqualTo(3);
		assertThat(ReputationGarden.stage(100, 100)).isEqualTo(3);
	}

	@Test
	void healthDimsOnlyWithPenalty() {
		assertThat(ReputationGarden.health(0)).isEqualTo(2);
		assertThat(ReputationGarden.health(1)).isEqualTo(1);
		assertThat(ReputationGarden.health(150)).isEqualTo(1);
		assertThat(ReputationGarden.health(151)).isZero();
		assertThat(ReputationGarden.health(600)).isZero();
	}

	@Test
	void eachComponentUsesItsOwnCap() {
		// 19.5 is 10% of trajectory (195) but only 6.5% of relations (300).
		assertThat(ReputationGarden.of(19.5, 19.5, 42, 120, 0))
			.isEqualTo(new ReputationGarden(1, 0, 1, 3, 2));
		assertThat(ReputationGarden.of(195, 210, 293, 41.9, 200))
			.isEqualTo(new ReputationGarden(3, 3, 2, 1, 0));
	}
}
