package com.socialcoisa.rede.jobs.domain;

import java.time.Instant;
import java.util.Objects;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Etapa do processo seletivo de UMA vaga. Cada vaga tem o fluxo que quiser — não existe
 * conjunto global de etapas. A ordem (`position`) é inteira e contígua; renomear não muda
 * identidade, então o histórico dos candidatos sobrevive à reorganização do fluxo.
 */
public class SelectionStage {

	public static final int MAX_NAME = 120;
	/** Fluxo de processo seletivo com dezenas de etapas é burocracia, não seleção. */
	public static final int MAX_STAGES_PER_JOB = 15;

	private final String id;
	private final String jobId;
	private String name;
	private int position;
	private final Instant createdAt;

	private SelectionStage(String id, String jobId, String name, int position, Instant createdAt) {
		this.id = Objects.requireNonNull(id, "id");
		this.jobId = Objects.requireNonNull(jobId, "vaga");
		this.createdAt = Objects.requireNonNull(createdAt, "criação");
		this.name = checkName(name);
		if (position < 0) {
			throw new ApiException(422, "INVALID_STAGE_POSITION", "A posição da etapa não pode ser negativa.");
		}
		this.position = position;
	}

	public static SelectionStage create(String id, String jobId, String name, int position, Instant now) {
		return new SelectionStage(id, jobId, name, position, now);
	}

	public static SelectionStage restore(String id, String jobId, String name, int position, Instant createdAt) {
		return new SelectionStage(id, jobId, name, position, createdAt);
	}

	private static String checkName(String value) {
		if (value == null || value.isBlank()) {
			throw new ApiException(422, "STAGE_NAME_REQUIRED", "A etapa precisa de um nome.");
		}
		String clean = value.trim();
		if (clean.length() > MAX_NAME) {
			throw new ApiException(422, "STAGE_NAME_TOO_LONG",
				"O nome da etapa deve ter no máximo " + MAX_NAME + " caracteres.");
		}
		return clean;
	}

	public SelectionStage renamed(String newName) {
		return new SelectionStage(id, jobId, checkName(newName), position, createdAt);
	}

	public SelectionStage atPosition(int newPosition) {
		return new SelectionStage(id, jobId, name, newPosition, createdAt);
	}

	public String id() {
		return id;
	}

	public String jobId() {
		return jobId;
	}

	public String name() {
		return name;
	}

	public int position() {
		return position;
	}

	public Instant createdAt() {
		return createdAt;
	}

}
