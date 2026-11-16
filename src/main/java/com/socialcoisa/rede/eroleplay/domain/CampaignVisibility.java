package com.socialcoisa.rede.eroleplay.domain;

/**
 * Quem pode entrar na campanha, e como.
 *
 * <p>PUBLIC entra na hora. INVITE_ONLY deixa o pedido pendente até uma decisão do mestre.
 * PRIVATE não tem entrada: só entra quem o mestre convida. Em nenhum dos casos "aberto" quer
 * dizer anônimo — entrar exige conta autenticada e verificada.</p>
 */
public enum CampaignVisibility {
	/** Só quem o mestre convida entra. */
	PRIVATE,
	/** Entra quem pede, depois de aprovado pelo mestre. */
	INVITE_ONLY,
	/** Entra direto, sem pedir. */
	PUBLIC;

	/** O pedido de entrada fica pendente até uma decisão do mestre. */
	public boolean requiresApproval() {
		return this == INVITE_ONLY;
	}

	/** A campanha aparece na descoberta pública. */
	public boolean discoverable() {
		return this == PUBLIC;
	}
}
