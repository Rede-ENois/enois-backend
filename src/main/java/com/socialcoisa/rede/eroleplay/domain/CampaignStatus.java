package com.socialcoisa.rede.eroleplay.domain;

/**
 * O estado da campanha — significado operacional, não rótulo de tela.
 *
 * <p>ACTIVE é o normal: aceita gente, aceita conteúdo, aparece na descoberta. ARCHIVED é a
 * campanha guardada: sai da descoberta e não aceita mais gente, mas o que já existe continua
 * legível para quem participa — o mestre arquiva para dar uma pausa sem perder o histórico.
 * CLOSED é o fim: a campanha não aceita mais nada e o conteúdo deixa de ser acessível.</p>
 *
 * <p>Quem consulta estes estados é o serviço, em cada operação. Uma campanha arquivada não
 * aceita participação nova por endpoint alternativo só porque a tela não mostrou o botão.</p>
 */
public enum CampaignStatus {
	ACTIVE, ARCHIVED, CLOSED;

	/** Entrada de novos participantes e pedido de entrada. */
	public boolean acceptsNewMembers() {
		return this == ACTIVE;
	}

	/** Aparece na descoberta pública — sem revelar nada do conteúdo interno. */
	public boolean visibleInDiscovery() {
		return this == ACTIVE;
	}

	/** O conteúdo que já existe continua legível para quem participa. */
	public boolean readableByMembers() {
		return this != CLOSED;
	}
}
