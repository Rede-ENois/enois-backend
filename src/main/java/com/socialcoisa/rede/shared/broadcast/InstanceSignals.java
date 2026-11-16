package com.socialcoisa.rede.shared.broadcast;

/**
 * Tipos de sinal que atravessam instâncias. Lista fechada, de propósito: sinal sem consumidor do
 * outro lado seria escrita em tabela que ninguém lê.
 */
public final class InstanceSignals {

	/** O stream de mensagens de um usuário mudou ("conversa", "aviso" ou "chamada"). */
	public static final String USER_EVENT = "UserEvent";

	/** A entrada de cache de um nome mudou: toda instância deve limpar o que tem. */
	public static final String CACHE_INVALIDATED = "CacheInvalidated";

	private InstanceSignals() {
	}
}
