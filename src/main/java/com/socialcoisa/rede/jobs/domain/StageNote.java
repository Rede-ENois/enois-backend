package com.socialcoisa.rede.jobs.domain;

import java.time.Instant;
import java.util.Objects;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Nota interna da equipe de recrutamento sobre uma candidatura. É avaliação entre membros
 * da empresa: o endpoint do candidato jamais a devolve, e a exclusão não é prevista — o
 * histórico interno é o registro da decisão.
 */
public class StageNote {

	public static final int MAX_BODY = 2000;

	private final Long id;
	private final long applicationId;
	private final String authorId;
	private final String body;
	private final Instant createdAt;

	public StageNote(Long id, long applicationId, String authorId, String body, Instant createdAt) {
		this.id = id;
		this.applicationId = applicationId;
		this.authorId = Objects.requireNonNull(authorId, "autor");
		String clean = body == null || body.isBlank() ? null : body.trim();
		if (clean == null) {
			throw new ApiException(422, "NOTE_BODY_REQUIRED", "A nota não pode ser vazia.");
		}
		if (clean.length() > MAX_BODY) {
			throw new ApiException(422, "NOTE_BODY_TOO_LONG",
				"A nota deve ter no máximo " + MAX_BODY + " caracteres.");
		}
		this.body = clean;
		this.createdAt = Objects.requireNonNull(createdAt, "data");
	}

	public Long id() {
		return id;
	}

	public long applicationId() {
		return applicationId;
	}

	public String authorId() {
		return authorId;
	}

	public String body() {
		return body;
	}

	public Instant createdAt() {
		return createdAt;
	}

}
