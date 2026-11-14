package com.socialcoisa.rede.eroleplay.domain;

/**
 * O papel de alguém DENTRO de uma campanha.
 *
 * <p>GAME_MASTER é o mestre: administra a campanha, convida, decide pedidos e enxerga tudo
 * (inclusive o que é segredo). PLAYER participa: joga, vê o que é público da campanha e nunca a
 * ficha de outro jogador.</p>
 *
 * <p>Papel de campanha não é privilégio de plataforma: nenhum destes alcança função global da
 * plataforma, e quem não tem papel nenhum não é participante — é visitante, e o que um visitante
 * enxerga de uma campanha pública é só a apresentação.</p>
 */
public enum CampaignRole {
	GAME_MASTER, PLAYER;

	/** O mestre administra a campanha; o jogador participa. */
	public boolean administrative() {
		return this == GAME_MASTER;
	}
}
