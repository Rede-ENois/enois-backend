package com.socialcoisa.rede.preferences.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A lista de temas válidos é o espelho do front: o backend recusa o que não conhece
 * (400), então um tema publicado na galeria sem entrar aqui não acompanha a pessoa
 * entre dispositivos. Estes testes travam as famílias da nova geração.
 */
class ThemeIdsTests {

	@Test
	void acceptsEveryThemeOfTheNewGeneration() {
		assertThat(ThemeIds.ALL).contains(
			"swing-metal", "swing-ocean", "swing-nimbus", "swing-motif", "swing-windows", "swing-dark",
			"web2000", "web2000-forum", "web2000-cybercafe", "web2000-glass", "web2000-portal",
			"web2000-pixel",
			"arcade-8bit", "arcade-16bit", "arcade-dungeon", "arcade-space", "arcade-wasteland",
			"arcade-strategy", "arcade-rpg", "arcade-terminal",
			"unix-classic", "terminal-green", "amber-crt", "workbench", "industrial-desktop");
	}

	@Test
	void keepsTheOriginalTwelveAndTheExpandedCollection() {
		assertThat(ThemeIds.ALL).contains(
			"win95", "win98", "win2000", "luna", "aero-glass", "frutiger-aero", "metro", "terminal",
			"vaporwave", "synthwave", "y2k", "neumorfismo");
		assertThat(ThemeIds.ALL).hasSize(85);
	}

	@Test
	void hasNoBlankOrPaddedId() {
		assertThat(ThemeIds.ALL)
			.allSatisfy(id -> assertThat(id).isNotBlank().isEqualTo(id.trim()));
	}
}
