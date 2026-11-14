package com.socialcoisa.rede.eroleplay.domain;

/**
 * O estado do pedido de rolagem: pendente de o jogador rolar, ou respondido.
 *
 * <p>Dois estados, de proposito: "cancelado" e "expirado" seriam um terceiro caminho para o mesmo
 * pedido deixar de valer, e cada caminho novo e uma chance de o registro contar uma historia
 * diferente da que aconteceu. O mestre que quiser desfazer um pedido faz outro e ignora este.</p>
 */
public enum RollRequestStatus {
	PENDING, ANSWERED
}
