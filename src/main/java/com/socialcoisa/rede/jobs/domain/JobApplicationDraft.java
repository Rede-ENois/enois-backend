package com.socialcoisa.rede.jobs.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Rascunho da candidatura: as respostas que a pessoa já digitou e ainda não enviou. Um por par
 * (candidato, vaga) — o autosave grava por cima, sem histórico; o que vale é o último estado.
 *
 * <p>Não é candidatura: não entra no board, não notifica ninguém, não conta como applied. Só
 * existe para a pessoa não perder o que digitou quando a aba cai ou o ônibus passa pelo túnel.
 */
public class JobApplicationDraft {

	/** Teto do rascunho inteiro (JSON): um formulário tem no máximo 25 campos de 2000. */
	static final int MAX_JSON_LENGTH = 100_000;

	private final String jobId;
	private final String userId;
	private final Map<String, String> answers;
	private final Instant updatedAt;

	private JobApplicationDraft(String jobId, String userId, Map<String, String> answers, Instant updatedAt) {
		this.jobId = jobId;
		this.userId = userId;
		this.answers = Map.copyOf(new LinkedHashMap<>(answers));
		this.updatedAt = updatedAt;
	}

	public static JobApplicationDraft create(String jobId, String userId, Map<String, String> answers,
			Instant now) {
		if (answers == null) {
			answers = Map.of();
		}
		if (answers.size() > 25) {
			throw new ApiException(422, "DRAFT_TOO_MANY_ANSWERS",
				"O rascunho não pode ter mais respostas do que o formulário da vaga.");
		}
		return new JobApplicationDraft(jobId, userId, answers, now);
	}

	public String jobId() {
		return jobId;
	}

	public String userId() {
		return userId;
	}

	public Map<String, String> answers() {
		return answers;
	}

	public Instant updatedAt() {
		return updatedAt;
	}
}
