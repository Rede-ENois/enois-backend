package com.socialcoisa.rede.eroleplay.domain;

/**
 * O estado de um pedido de entrada na campanha.
 *
 * <p>PENDING espera decisão do mestre. APPROVED e REJECTED são finais: um pedido decidido não
 * volta a pendente, e a decisão é registrada com quem decidiu e quando.</p>
 */
public enum CampaignJoinRequestStatus {
	PENDING, APPROVED, REJECTED, CANCELLED
}
