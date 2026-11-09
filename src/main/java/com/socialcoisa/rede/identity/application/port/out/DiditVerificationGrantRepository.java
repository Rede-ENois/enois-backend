package com.socialcoisa.rede.identity.application.port.out;

import java.time.Instant;

/**
 * Quem pode abrir uma verificacao na Didit.
 *
 * <p>A liberacao e progressiva: so quem recebeu um convite pode iniciar. O convite guarda o
 * motivo da liberacao (rodada, credibilidade no momento) para que a decisao seja auditavel e
 * reproduzivel — e nunca e concedido a mao, sem a rodada.</p>
 */
public interface DiditVerificationGrantRepository {

	boolean hasGrant(String userId);

	/** Cria o convite; se ja existir, nao mexe na data original. */
	void grant(String userId, String roundId, int reputation, Instant now);

	/** Sessao iniciada: marca o consumo do convite. */
	void markConsumed(String userId, Instant now);

	void revoke(String userId, Instant now);
}
