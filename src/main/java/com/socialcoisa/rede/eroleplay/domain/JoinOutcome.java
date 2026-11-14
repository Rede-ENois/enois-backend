package com.socialcoisa.rede.eroleplay.domain;

/**
 * O desfecho de um pedido de entrada na campanha.
 *
 * <p>MEMBER é a associação criada na hora; PENDING é o pedido esperando o mestre decidir. Não há
 * "recusado" aqui: o desfecho de um pedido recusado é a ausência de associação, e o histórico do
 * pedido guarda a decisão.</p>
 */
public enum JoinOutcome {
	MEMBER, PENDING
}
