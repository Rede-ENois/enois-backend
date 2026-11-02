package com.socialcoisa.rede.contributions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.contributions.domain.ContributionCategory;
import com.socialcoisa.rede.contributions.domain.SealType;

/** Os limites públicos da tabela de critérios, um a um, para as duas categorias. */
class SealTypeTests {

	@Test
	void codeTiersFollowThePublishedBoundaries() {
		assertThat(SealType.forCount(ContributionCategory.CODE, 0)).isNull();
		assertThat(SealType.forCount(ContributionCategory.CODE, 1)).isEqualTo(SealType.AJUDANTE);
		assertThat(SealType.forCount(ContributionCategory.CODE, 10)).isEqualTo(SealType.AJUDANTE);
		assertThat(SealType.forCount(ContributionCategory.CODE, 11)).isEqualTo(SealType.CONTRIBUINTE_SERIO);
		assertThat(SealType.forCount(ContributionCategory.CODE, 50)).isEqualTo(SealType.CONTRIBUINTE_SERIO);
		assertThat(SealType.forCount(ContributionCategory.CODE, 51)).isEqualTo(SealType.HARDCORE);
		assertThat(SealType.forCount(ContributionCategory.CODE, 100)).isEqualTo(SealType.HARDCORE);
		assertThat(SealType.forCount(ContributionCategory.CODE, 101)).isEqualTo(SealType.TRYHARD);
		assertThat(SealType.forCount(ContributionCategory.CODE, 1000)).isEqualTo(SealType.TRYHARD);
		assertThat(SealType.forCount(ContributionCategory.CODE, 1001)).isEqualTo(SealType.GET_A_LIFE);
	}

	@Test
	void issueTiersFollowThePublishedBoundaries() {
		assertThat(SealType.forCount(ContributionCategory.ISSUES, 0)).isNull();
		assertThat(SealType.forCount(ContributionCategory.ISSUES, 1)).isEqualTo(SealType.BETA_TESTER);
		assertThat(SealType.forCount(ContributionCategory.ISSUES, 10)).isEqualTo(SealType.BETA_TESTER);
		assertThat(SealType.forCount(ContributionCategory.ISSUES, 11)).isEqualTo(SealType.EXIGENTE);
		assertThat(SealType.forCount(ContributionCategory.ISSUES, 20)).isEqualTo(SealType.EXIGENTE);
		assertThat(SealType.forCount(ContributionCategory.ISSUES, 21)).isEqualTo(SealType.ESFORCO_MAXIMO);
		assertThat(SealType.forCount(ContributionCategory.ISSUES, 50)).isEqualTo(SealType.ESFORCO_MAXIMO);
		assertThat(SealType.forCount(ContributionCategory.ISSUES, 51)).isEqualTo(SealType.PLUS_ULTRA);
		assertThat(SealType.forCount(ContributionCategory.ISSUES, 100)).isEqualTo(SealType.PLUS_ULTRA);
		assertThat(SealType.forCount(ContributionCategory.ISSUES, 101)).isEqualTo(SealType.GENKIDAMA);
	}

	@Test
	void categoriesDoNotLeakTiersIntoEachOther() {
		assertThat(SealType.of(ContributionCategory.CODE)).hasSize(5)
			.allMatch(type -> type.category() == ContributionCategory.CODE);
		assertThat(SealType.of(ContributionCategory.ISSUES)).hasSize(5)
			.allMatch(type -> type.category() == ContributionCategory.ISSUES);
		assertThat(SealType.forCount(ContributionCategory.ISSUES, 1001)).isEqualTo(SealType.GENKIDAMA);
		assertThat(SealType.forCount(ContributionCategory.DONATION, 10)).isNull();
	}
	@Test
	void everyTierHasItsOwnFixedEmblem() {
		assertThat(SealType.values()).extracting(SealType::emblem)
			.doesNotContainNull()
			.doesNotHaveDuplicates()
			.containsExactly("ajudante", "contribuinte-serio", "hardcore", "tryhard", "get-a-life",
				"beta-tester", "exigente", "esforco-maximo", "plus-ultra", "genkidama");
	}
}