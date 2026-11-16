package com.socialcoisa.rede.jobs.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Avaliação interna de uma candidatura, feita por um membro da equipe de recrutamento. É o
 * parecer estruturado que a nota livre não cobre: critérios com nota de 0 a 10, veredito e
 * resumo. Uma por autor por candidatura — reavaliar sobrescreve a anterior, porque o que
 * interessa é a posição atual de cada avaliador (o histórico da decisão vive nos movimentos).
 *
 * <p>O endpoint do candidato jamais a devolve, como as notas.
 */
public class ApplicationEvaluation {

	/** Veredito do avaliador sobre a candidatura. */
	public enum Verdict {
		STRONG_YES, YES, MAYBE, NO
	}

	public static final int MAX_CRITERIA = 10;
	public static final int MAX_SCORE = 10;
	public static final int MAX_SUMMARY = 2000;

	private final Long id;
	private final long applicationId;
	private final String authorId;
	private final Map<String, Integer> criteria;
	private final Verdict verdict;
	private final String summary;
	private final Instant createdAt;
	private final Instant updatedAt;

	public ApplicationEvaluation(Long id, long applicationId, String authorId, Map<String, Integer> criteria,
			Verdict verdict, String summary, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.applicationId = applicationId;
		this.authorId = Objects.requireNonNull(authorId, "autor");
		if (criteria == null || criteria.isEmpty()) {
			throw new ApiException(422, "EVALUATION_CRITERIA_REQUIRED",
				"A avaliação precisa de pelo menos um critério.");
		}
		if (criteria.size() > MAX_CRITERIA) {
			throw new ApiException(422, "EVALUATION_TOO_MANY_CRITERIA",
				"A avaliação aceita no máximo " + MAX_CRITERIA + " critérios.");
		}
		Map<String, Integer> clean = new LinkedHashMap<>();
		for (var entry : criteria.entrySet()) {
			String label = entry.getKey() == null ? "" : entry.getKey().trim();
			if (label.isEmpty()) {
				throw new ApiException(422, "EVALUATION_CRITERIA_LABEL_REQUIRED",
					"Todo critério precisa de um nome.");
			}
			if (label.length() > 80) {
				throw new ApiException(422, "EVALUATION_CRITERIA_LABEL_TOO_LONG",
					"O nome do critério deve ter no máximo 80 caracteres.");
			}
			Integer score = entry.getValue();
			if (score == null || score < 0 || score > MAX_SCORE) {
				throw new ApiException(422, "EVALUATION_SCORE_OUT_OF_RANGE",
					"A nota de cada critério vai de 0 a " + MAX_SCORE + ".");
			}
			clean.put(label, score);
		}
		this.criteria = Map.copyOf(clean);
		this.verdict = Objects.requireNonNull(verdict, "veredito");
		if (summary != null && summary.length() > MAX_SUMMARY) {
			throw new ApiException(422, "EVALUATION_SUMMARY_TOO_LONG",
				"O parecer deve ter no máximo " + MAX_SUMMARY + " caracteres.");
		}
		this.summary = summary == null || summary.isBlank() ? null : summary.strip();
		this.createdAt = Objects.requireNonNull(createdAt, "data de criação");
		this.updatedAt = Objects.requireNonNull(updatedAt, "data de atualização");
	}

	/** Avaliação nova: as duas datas nascem do mesmo relógio. */
	public static ApplicationEvaluation create(long applicationId, String authorId,
			Map<String, Integer> criteria, Verdict verdict, String summary, Instant now) {
		return new ApplicationEvaluation(null, applicationId, authorId, criteria, verdict, summary, now, now);
	}

	/** Avaliação guardada, reavaliada pelo mesmo autor: identidade e datas de criação ficam. */
	public ApplicationEvaluation reevaluate(Map<String, Integer> criteria, Verdict verdict, String summary,
			Instant now) {
		return new ApplicationEvaluation(id, applicationId, authorId, criteria, verdict, summary, createdAt, now);
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

	public Map<String, Integer> criteria() {
		return criteria;
	}

	public Verdict verdict() {
		return verdict;
	}

	public String summary() {
		return summary;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

}
