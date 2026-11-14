package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.jobs.domain.StageMovement;
import com.socialcoisa.rede.jobs.domain.StageMovementKind;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "stage_movements")
class StageMovementJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "application_id", nullable = false)
	private long applicationId;

	@Column(name = "job_id", nullable = false)
	private String jobId;

	@Column(name = "from_stage_id")
	private String fromStageId;

	@Column(name = "to_stage_id", nullable = false, length = 64)
	private String toStageId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private StageMovementKind kind;

	@Column(name = "actor_id", nullable = false)
	private String actorId;

	@Column(length = 1000)
	private String note;

	@Column(name = "moved_at", nullable = false)
	private Instant movedAt;

	protected StageMovementJpaEntity() {
	}

	static StageMovementJpaEntity fromDomain(StageMovement movement) {
		StageMovementJpaEntity e = new StageMovementJpaEntity();
		e.applicationId = movement.applicationId();
		e.jobId = movement.jobId();
		e.fromStageId = movement.fromStageId();
		e.toStageId = movement.toStageId();
		e.kind = movement.kind();
		e.actorId = movement.actorId();
		e.note = movement.note();
		e.movedAt = movement.movedAt();
		return e;
	}

	StageMovement toDomain() {
		return new StageMovement(id, applicationId, jobId, fromStageId, toStageId, kind, actorId, note, movedAt);
	}

}
