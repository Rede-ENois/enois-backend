package com.socialcoisa.rede.jobs.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Um movimento do candidato no pipeline: quem moveu, de onde para onde, quando e por quê.
 * É a linha do histórico que o candidato acompanha — sem nota interna nunca aqui.
 */
public class StageMovement {

	/** Limite do texto de contexto que acompanha o movimento (visível ao candidato na reprovação). */
	public static final int MAX_NOTE = 1000;

	private final Long id;
	private final long applicationId;
	private final String jobId;
	private final String fromStageId;
	private final String toStageId;
	private final StageMovementKind kind;
	private final String actorId;
	private final String note;
	private final Instant movedAt;

	public StageMovement(Long id, long applicationId, String jobId, String fromStageId, String toStageId,
			StageMovementKind kind, String actorId, String note, Instant movedAt) {
		this.id = id;
		this.applicationId = applicationId;
		this.jobId = Objects.requireNonNull(jobId, "vaga");
		this.fromStageId = fromStageId;
		this.toStageId = Objects.requireNonNull(toStageId, "etapa de destino");
		this.kind = Objects.requireNonNull(kind, "espécie");
		this.actorId = Objects.requireNonNull(actorId, "responsável");
		this.note = checkNote(note);
		this.movedAt = Objects.requireNonNull(movedAt, "data");
	}

	private static String checkNote(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String clean = value.trim();
		if (clean.length() > MAX_NOTE) {
			throw new com.socialcoisa.rede.shared.web.ApiException(422, "MOVEMENT_NOTE_TOO_LONG",
				"A anotação do movimento deve ter no máximo " + MAX_NOTE + " caracteres.");
		}
		return clean;
	}

	public Long id() {
		return id;
	}

	public long applicationId() {
		return applicationId;
	}

	public String jobId() {
		return jobId;
	}

	public String fromStageId() {
		return fromStageId;
	}

	public String toStageId() {
		return toStageId;
	}

	public StageMovementKind kind() {
		return kind;
	}

	public String actorId() {
		return actorId;
	}

	public String note() {
		return note;
	}

	public Instant movedAt() {
		return movedAt;
	}

}
