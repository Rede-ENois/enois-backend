package com.socialcoisa.rede.calls.application.port.out;

/**
 * A mesa do Éroleplay, lida de fora do RPG sem arrastar o serviço dele para as chamadas.
 *
 * <p>A mesa é a sala de jogo dentro de uma campanha. Quem pode entrar na voz é quem participa da
 * campanha — a conferência é feita aqui, no banco, a cada pedido: id inventado não abre sala e
 * campanha estranha não herda a voz de mesa alheia.
 */
public interface TableCallLookup {

	/**
	 * Devolve a campanha da mesa, provando que a mesa existe.
	 *
	 * @throws com.socialcoisa.rede.shared.web.ApiException 404 quando a mesa não existe
	 */
	String requireCampaignOf(String tableId);

	/**
	 * Quem participa da campanha da mesa: a audiência do aviso "a mesa está ao vivo". A entrada
	 * na sala continua conferida pessoa a pessoa por {@link #requireMemberOfCampaign}.
	 */
	java.util.List<String> campaignMembersOf(String tableId);

	/**
	 * A mesa existe E a pessoa participa da campanha dela — a regra de entrada na voz da mesa,
	 * conferida no banco a cada pedido.
	 *
	 * @throws com.socialcoisa.rede.shared.web.ApiException 404 quando a mesa não existe; 403 quando
	 * existe mas quem pede não participa da campanha
	 */
	void requireMemberOfCampaign(String tableId, String userId);
}
