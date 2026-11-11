package com.socialcoisa.rede.shared.broadcast;

/**
 * Entrega o sinal no estado local <b>desta</b> instância.
 *
 * <p>Uma implementação por tipo de estado em memória — o hub dos streams e o cache. O contrato é
 * deliberadamente estreito: recebe o sinal, aplica no que só existe no processo, nunca vai ao banco
 * buscar conteúdo. Sinal é aviso de "mudou", não conteúdo.</p>
 *
 * <p>A entrega precisa ser idempotente do ponto de vista do estado local: o mesmo sinal aplicado
 * duas vezes deixa o estado igual. É por isso que "limpar o cache" e "avisar o stream" são
 * operações seguras de repetir.</p>
 */
public interface LocalSignalConsumer {

	/** Tipo de sinal que esta implementação entrega (ver {@link InstanceSignals}). */
	String signalType();

	/**
	 * Aplica o sinal no estado local.
	 *
	 * @param signal sinal lido por esta instância, garantidamente não emitido por ela
	 */
	void deliver(InstanceSignal signal);
}
