package com.socialcoisa.rede.network.domain;

import java.time.Instant;

/**
 * Uma transferência de réplica entre nós (brief §16, V354): a cópia {@code fromReplica} sai do nó
 * {@code fromNodeId} e uma nova cópia nasce no nó {@code toNodeId}.
 *
 * <p><b>A regra que este registro existe para proteger é a do fim do §16:</b> nunca remover a última
 * cópia saudável antes de confirmar a persistência e a integridade do destino. Por isso a cópia
 * antiga só é marcada {@code GONE} no passo em que o destino <b>confirmou o digest</b> — não na
 * designação, e nunca "porque a tarefa foi enviada".</p>
 *
 * <p>A migração é <b>sem indisponibilidade</b> pelo mesmo motivo: a cópia antiga continua servível
 * durante toda a transferência, e só deixa de existir quando já há outra verificada.</p>
 */
public record StorageRebalance(String id, String objectId, int fromReplica, String fromNodeId, int toReplica,
		String toNodeId, State state, String failureReason, long bytes, Integer bandwidthLimitMbps,
		String requestedBy, Instant createdAt, Instant finishedAt) {

	/**
	 * Estado da transferência.
	 *
	 * <p>{@code PENDING} é a única que costuma a ter cópia antiga ainda servível. {@code CANCELLED} e
	 * {@code FAILED} também deixam a antiga: desfazer uma transferência não pode ter removido nada.</p>
	 */
	public enum State {
		/** Designada: a cópia nova está em {@code PENDING_REMOTE} e a antiga continua {@code OK}. */
		PENDING,
		/** O destino confirmou o digest e a cópia antiga foi removida. */
		DONE,
		/** Desfeita pelo operador antes da confirmação; a antiga fica. */
		CANCELLED,
		/** O destino recusou ou a verificação falhou; a antiga fica. */
		FAILED;

		/** A transferência ainda pode ser desfeita sem ter tocado a cópia antiga. */
		public boolean active() {
			return this == PENDING;
		}
	}

	/** A transferência já terminou (de qualquer jeito) e não deve ser retomada. */
	public boolean finished() {
		return !state.active();
	}
}