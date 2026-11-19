package com.socialcoisa.rede.network.domain;

/**
 * Os tipos de storage node do brief §6, como <b>comportamento</b> em vez de nome de perfil.
 *
 * <p>Separado de {@link NodeProfile} porque o perfil é o vocabulário que o admin e o nó usam, e o
 * tipo é o que a colocação precisa decidir. Sem essa camada, `switch` sobre nome de perfil se
 * espalharia por cada serviço.
 *
 * <p>{@code NODE_STORAGE} e {@code NODE_STORAGE_STANDARD} são o mesmo tipo: a especialização é
 * aditiva sobre o que já existia.</p>
 */
public enum StorageNodeKind {

	/** Convencional: leitura e escrita, qualquer objeto. */
	STANDARD,

	/** Imagens, vídeos e miniaturas. */
	MEDIA,

	/** Longo prazo: prioriza capacidade e custo. */
	ARCHIVE,

	/** Perto dos usuários: prioriza leitura e distribuição. */
	EDGE,

	/** Mantém cópias redundantes. */
	REPLICA,

	/**
	 * Servidor doméstico: disponibilidade variável, banda limitada, NAT.
	 *
	 * <p>Declarativo nesta rodada — ver {@code docs/rede-distribuida/18-…}, a seção das lacunas.</p>
	 */
	HOME
}
