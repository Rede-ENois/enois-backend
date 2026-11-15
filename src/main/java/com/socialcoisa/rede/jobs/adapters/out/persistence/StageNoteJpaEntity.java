package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.jobs.domain.StageNote;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "stage_notes")
class StageNoteJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "application_id", nullable = false)
	private long applicationId;

	@Column(name = "author_id", nullable = false)
	private String authorId;

	@Column(nullable = false, length = 2000)
	private String body;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected StageNoteJpaEntity() {
	}

	static StageNoteJpaEntity fromDomain(StageNote note) {
		StageNoteJpaEntity e = new StageNoteJpaEntity();
		e.applicationId = note.applicationId();
		e.authorId = note.authorId();
		e.body = note.body();
		e.createdAt = note.createdAt();
		return e;
	}

	StageNote toDomain() {
		return new StageNote(id, applicationId, authorId, body, createdAt);
	}

}
