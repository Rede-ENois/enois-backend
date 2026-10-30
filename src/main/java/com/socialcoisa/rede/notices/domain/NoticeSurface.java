package com.socialcoisa.rede.notices.domain;

/**
 * Onde o aviso aparece. O admin escolhe na hora de publicar; o front não decide sozinho,
 * senão um aviso escrito para virar faixa aparecia como modal na cara de todo mundo.
 */
public enum NoticeSurface {
	/** Faixa no topo, sempre visível até a pessoa fechar. */
	BANNER,
	/** Popup que abre uma vez por edição do aviso. */
	MODAL,
	/** Os dois: faixa permanente + popup para quem não está olhando o topo. */
	BOTH;

	public boolean showsBanner() {
		return this == BANNER || this == BOTH;
	}

	public boolean showsModal() {
		return this == MODAL || this == BOTH;
	}
}
