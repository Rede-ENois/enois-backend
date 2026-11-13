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
 *
 * @param region região de origem declarada por quem pediu a tarefa; {@code null} = sem preferência.
 * O scheduler a usa como <b>desempate de ordenação</b>, nunca como filtro (ADR-012): pedido feito na
 * região X roda primeiro em nó de X, e roda em qualquer nó saudável se não houver X vivo.
 * @param targetNodeId nó a que a tarefa pertence; {@code null} = qualquer nó elegível. É <b>filtro</b>,
 * ao contrário de {@code region}, porque o trabalho pode depender do estado local daquele nó (o
 * cursor do cache de borda vive nele). Alvo fora dos candidatos deixa a tarefa na fila: executar no
 * nó errado não é executar pior, é não executar.
 */
public record NetworkTask(String id, TaskType taskType, TaskStatus status, int attempt, String idempotencyKey,
		String payload, DataClass dataClass, String region, String assignedNodeId, Instant deadline,
		Instant nextAttemptAt, Instant dispatchedAt, Instant claimExpiresAt, Instant finishedAt, String output,
		String lastError, Instant createdAt, Instant updatedAt, String targetNodeId) {

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

	/** A tarefa pertence a este nó e a nenhum outro? */
	public boolean targetedAt(String nodeId) {
		return targetNodeId != null && targetNodeId.equals(nodeId);
	}

	/** Quanto falta para o prazo; negativo quando já venceu. */
	public Duration remaining(Instant now) {
		return Duration.between(now, deadline);
	}
}
