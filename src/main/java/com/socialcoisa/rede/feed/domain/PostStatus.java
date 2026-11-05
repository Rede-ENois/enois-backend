package com.socialcoisa.rede.feed.domain;

public enum PostStatus {
	ACTIVE,
	/**
	 * Sob revisão da moderação: some do feed, do perfil e do detalhe, mas continua no banco e volta
	 * ao ar se a revisão humana concluir que não havia violação. Não é exclusão — a conta não é
	 * punida e o conteúdo não é apagado.
	 */
	QUARANTINED,
	DELETING,
	DELETED
}
