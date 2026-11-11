package com.socialcoisa.rede.network.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Uma tarefa distribuída: a unidade de trabalho que sai do Control Plane para um nó.
 *
 * <p>O {@code id} é estável entre tentativas e o {@code attempt} sobe a cada designação. É isso que
 * permite redistribuir sem duplicar efeito: o nó recebe o mesmo {@code id} com {@code attempt}
 * maior, e o resultado de uma tentativa antiga é descartado porque o {@code attempt} não confere
 * mais.</p>
 *
 * <p>O estado de retentativa vive aqui, não no evento do outbox: o worker do outbox grava o recibo
 * do handler antes de chamá-lo, então quando o nó falha o evento já está concluído. Quem sabe que a
 * tarefa falhou é esta linha (ADR-006).</p>
 */
public record NetworkTask(String id, TaskType taskType, TaskStatus status, int attempt, String idempotencyKey,
		String payload, DataClass dataClass, String assignedNodeId, Instant deadline, Instant nextAttemptAt,
		Instant dispatchedAt, Instant claimExpiresAt, Instant finishedAt, String output, String lastError,
		Instant createdAt, Instant updatedAt) {

	/** Erro guardado na linha: o suficiente para o painel dizer o que houve, sem payload. */
	public static final int MAX_ERROR_LENGTH = 500;

	public NetworkTask {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("Task id vazio.");
		}
		if (taskType == null) {
			throw new IllegalArgumentException("Task type nulo.");
		}
		if (status == null) {
			throw new IllegalArgumentException("Task status nulo.");
		}
		if (dataClass == null) {
			throw new IllegalArgumentException("Task dataClass nula.");
		}
		if (deadline == null) {
			throw new IllegalArgumentException("Task deadline nula.");
		}
	}

	/** Venceu o prazo? Tarefa vencida não executa e não volta para a fila. */
	public boolean expired(Instant now) {
		return !deadline.isAfter(now);
	}

	/** O nó designado ainda tem a tarefa reservada? Claim vencido devolve a tarefa à fila. */
	public boolean claimExpired(Instant now) {
		return claimExpiresAt != null && !claimExpiresAt.isAfter(now);
	}

	/** Ainda pode ser designada (na fila e no prazo)? */
	public boolean dispatchable(Instant now) {
		return status == TaskStatus.PENDING && !expired(now) && !nextAttemptAt.isAfter(now);
	}

	/** O resultado que chegou é da tentativa vigente? Resultado de tentativa antiga é descartado. */
	public boolean matchesAttempt(int reportedAttempt) {
		return reportedAttempt == attempt;
	}

	/** O nó que devolveu o resultado é o que estava designado? */
	public boolean assignedTo(String nodeId) {
		return assignedNodeId != null && assignedNodeId.equals(nodeId);
	}

	/** Quanto falta para o prazo; negativo quando já venceu. */
	public Duration remaining(Instant now) {
		return Duration.between(now, deadline);
	}
}
