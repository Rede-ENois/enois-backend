package com.socialcoisa.rede.network.domain;

/** Estado da verificação de um transcode delegado a um nó. */
public enum MediaJobStatus {

	/** O nó ainda não entregou um resultado que confira. */
	PENDING,

	/** Hash e tamanho medidos no objeto conferem com o que o nó declarou. */
	VERIFIED,

	/** O nó entregou um resultado que não confere; a tarefa volta para a fila. */
	REJECTED
}
