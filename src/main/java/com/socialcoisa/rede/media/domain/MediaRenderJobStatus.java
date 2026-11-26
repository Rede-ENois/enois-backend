package com.socialcoisa.rede.media.domain;

/** Estado do render de páginas de um PDF. */
public enum MediaRenderJobStatus {

	/** Pedido registrado, páginas ainda não gravadas. */
	PENDING,
	/** Todas as páginas foram renderizadas e gravadas — agora dependem da checagem de segurança. */
	RENDERED,
	/**
	 * Não foi possível renderizar. O documento fica SEM página visível: a peça que o usa continua
	 * bloqueada, porque "não deu para ver" nunca vale como "está limpo".
	 */
	FAILED
}
