package com.socialcoisa.rede.eroleplay.domain;

/**
 * Quem está lendo uma ficha — o público da leitura, não o papel na campanha.
 *
 * <p>Existe separado de {@link CampaignRole} porque as duas coisas não coincidem: o mestre da
 * campanha e o dono da ficha podem ser a mesma pessoa, e o que ela lê nesse caso é mais do que o
 * mestre lê da ficha alheia. Quem decide o público é o serviço, olhando de quem é a ficha; o que
 * cada público alcança é regra de domínio, aqui.</p>
 */
public enum SheetAudience {

	/** Quem preencheu: alcança a própria ficha inteira, inclusive os campos privados. */
	OWNER,

	/** O mestre da campanha: alcança o que foi aberto ao mestre e à campanha, nunca o privado. */
	GAME_MASTER,

	/** Outro participante: alcança apenas o que foi aberto à campanha. */
	MEMBER;

	/**
	 * Se este público enxerga um campo com a visibilidade informada.
	 *
	 * <p>A regra é escrita por público, e não por comparação de ordem, porque "privado" não é o
	 * degrau mais fechado de uma escada — é a exceção do dono. Ordem sozinha deixaria o mestre ler
	 * o segredo do jogador, que é justamente o que o jogador fechou.</p>
	 */
	public boolean sees(SheetVisibility visibility) {
		if (visibility == null) {
			return false;
		}
		return switch (this) {
			case OWNER -> true;
			case GAME_MASTER -> visibility != SheetVisibility.PRIVATE;
			case MEMBER -> visibility == SheetVisibility.CAMPAIGN;
		};
	}
}