package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.jobs.domain.SelectionStage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "selection_stages")
class SelectionStageJpaEntity {

	@Id
	private String id;

	@Column(name = "job_id", nullable = false)
	private String jobId;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(nullable = false)
	private int position;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected SelectionStageJpaEntity() {
	}

	static SelectionStageJpaEntity fromDomain(SelectionStage stage) {
		SelectionStageJpaEntity e = new SelectionStageJpaEntity();
		e.id = stage.id();
		e.jobId = stage.jobId();
		e.name = stage.name();
		e.position = stage.position();
		e.createdAt = stage.createdAt();
		return e;
	}

	void applyPositionAndName(int newPosition, String newName) {
		this.position = newPosition;
		this.name = newName;
	}

	SelectionStage toDomain() {
		return SelectionStage.restore(id, jobId, name, position, createdAt);
	}

}
