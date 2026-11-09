package com.socialcoisa.rede.identity.application.port.out;

/**
 * Porta da liberacao progressiva, vista pelo fluxo de verificacao.
 *
 * <p>Fica separada de proposito: o servico de verificacao so precisa saber "esta liberado?",
 * "marcar consumo" e "ha capacidade no ciclo". Quem decide a fila e as rodadas e outro servico, e
 * assim a maquina de estados continua testavel sem banco.</p>
 */
public interface IdentityReleaseGate {

	boolean granted(String userId);

	void consume(String userId);

	/**
	 * Lanca 503 quando o ciclo acabou. Chamado apenas para provedor pago e sempre ANTES da
	 * chamada externa, para nao gerar cobranca sem capacidade.
	 */
	void requireCapacity();
}