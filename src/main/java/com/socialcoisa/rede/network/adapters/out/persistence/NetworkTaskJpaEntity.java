package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.TaskStatus;
import com.socialcoisa.rede.network.domain.TaskType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "network_tasks")
class NetworkTaskJpaEntity {

	@Id
	@Column(length = 40)
	private String id;

	@Column(name = "task_type", nullable = false, length = 32)
	private String taskType;

	@Column(nullable = false, length = 16)
	private String status;

	@Column(nullable = false)
	private int attempt;

	@Column(name = "idempotency_key", nullable = false, length = 80)
	private String idempotencyKey;

	@Column(nullable = false, columnDefinition = "longtext")
	private String payload;

	@Column(name = "data_class", nullable = false, length = 24)
	private String dataClass;

	@Column(name = "assigned_node_id", length = 40)
	private String assignedNodeId;

	@Column(nullable = false)
	private Instant deadline;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "dispatched_at")
	private Instant dispatchedAt;

	@Column(name = "claim_expires_at")
	private Instant claimExpiresAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	@Column(columnDefinition = "longtext")
	private String output;

	@Column(name = "last_error", length = 500)
	private String lastError;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected NetworkTaskJpaEntity() {
	}

	NetworkTaskJpaEntity(String id, String taskType, String idempotencyKey, String payload, String dataClass,
			Instant deadline, Instant nextAttemptAt, Instant now) {
		this.id = id;
		this.taskType = taskType;
		this.status = TaskStatus.PENDING.name();
		this.attempt = 0;
		this.idempotencyKey = idempotencyKey;
		this.payload = payload;
		this.dataClass = dataClass;
		this.deadline = deadline;
		this.nextAttemptAt = nextAttemptAt;
		this.createdAt = now;
		this.updatedAt = now;
	}

	NetworkTask toDomain() {
		return new NetworkTask(id, TaskType.valueOf(taskType), TaskStatus.valueOf(status), attempt, idempotencyKey,
			payload, DataClass.valueOf(dataClass), assignedNodeId, deadline, nextAttemptAt, dispatchedAt,
			claimExpiresAt, finishedAt, output, lastError, createdAt, updatedAt);
	}

	String status() {
		return status;
	}
}
