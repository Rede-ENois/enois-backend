package com.socialcoisa.rede.doacoes.domain;

/**
 * O status de uma doação.
 *
 * <p>Máquina de estados pequena de propósito: o doador pede o copia e cola ({@code ABERTA});
 * confirmar (extrato do recebedor) ou o doador colar o comprovante leva a {@code CONFIRMADA}; uma
 * pessoa com o extrato da conta leva a {@code RECUSADA} (não apareceu na conta). Só confirmada vale
 * selo.</p>
 */
public enum DoacaoStatus {
	ABERTA,
	CONFIRMADA,
	RECUSADA;

	/** Como o estado foi alcançado — o extrato da conta é a prova forte. */
	public enum Origem {
		CONTA,
		DOADOR,
		REVISAO,
		/**
		 * Selo registrado à mão por um administrador: nenhum comprovante foi lido. A linha tem
		 * valor zero e existe só para o selo — nunca entra como dinheiro recebido.
		 */
		MANUAL
	}
}
