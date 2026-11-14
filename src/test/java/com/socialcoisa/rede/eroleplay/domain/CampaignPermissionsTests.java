package com.socialcoisa.rede.eroleplay.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A régua de autorização do Éroleplay, testada no domínio.
 *
 * <p>Estas asserções são a prova de que a separação entre mestre e jogador é do motor, e não da
 * tela: a mesma tabela decide para todo endpoint que pergunta por uma capacidade.</p>
 */
class CampaignPermissionsTests {

	@Test
	void oVisitanteNaoAlcancaNadaDaCampanha() {
		for (CampaignCapability capability : CampaignCapability.values()) {
			assertThat(CampaignPermissions.can(null, capability))
				.as("visitante nao pode %s", capability)
				.isFalse();
		}
	}

	@Test
	void oMestreAlcancaTudoDaCampanha() {
		for (CampaignCapability capability : CampaignCapability.values()) {
			assertThat(CampaignPermissions.can(CampaignRole.GAME_MASTER, capability))
				.as("mestre pode %s", capability)
				.isTrue();
		}
	}

	@Test
	void oJogadorVedeEJogaMasNaoAdministra() {
		assertThat(CampaignPermissions.can(CampaignRole.PLAYER, CampaignCapability.VIEW_CAMPAIGN)).isTrue();
		assertThat(CampaignPermissions.can(CampaignRole.PLAYER, CampaignCapability.POST_IN_CAMPAIGN)).isTrue();
		assertThat(CampaignPermissions.can(CampaignRole.PLAYER, CampaignCapability.ROLL_DICE)).isTrue();

		assertThat(CampaignPermissions.can(CampaignRole.PLAYER, CampaignCapability.MANAGE_CAMPAIGN)).isFalse();
		assertThat(CampaignPermissions.can(CampaignRole.PLAYER, CampaignCapability.MANAGE_MEMBERS)).isFalse();
		assertThat(CampaignPermissions.can(CampaignRole.PLAYER, CampaignCapability.MANAGE_TABLES)).isFalse();
		assertThat(CampaignPermissions.can(CampaignRole.PLAYER, CampaignCapability.TRANSFER_MASTERSHIP)).isFalse();
	}

	@Test
	void aFamiliaDoSistemaEInterpretadaEConhecida() {
		assertThat(GameSystemFamily.of("d20")).isEqualTo(GameSystemFamily.D20);
		assertThat(GameSystemFamily.of(" D100 ")).isEqualTo(GameSystemFamily.D100);
		assertThat(GameSystemFamily.of("custom")).isEqualTo(GameSystemFamily.CUSTOM);
	}

	@Test
	void umaFamiliaDesconhecidaNaoViraAFamiliaPadrao() {
		assertThatThrownBy(() -> GameSystemFamily.of("D777"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("D777");
		assertThatThrownBy(() -> GameSystemFamily.of(null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GameSystemFamily.of("  "))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void oEnderecoDaCampanhaVemDoNome() {
		assertThat(CampaignSlug.from("Crônicas & Cia")).isEqualTo("cronicas-cia");
		assertThat(CampaignSlug.from("!!!")).isEqualTo("campanha");
		assertThat(CampaignSlug.from("Vale do Silêncio")).isEqualTo("vale-do-silencio");
		assertThat(CampaignSlug.isReserved("mine")).isTrue();
		assertThat(CampaignSlug.isReserved("outro")).isFalse();
	}
}
