package com.socialcoisa.rede.talentbank.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Um evento da trilha de uma banca ou de uma inscrição.
 *
 * <p>A trilha é append-only e legível pelas duas partes: a pessoa vê o que aconteceu com a inscrição
 * dela, o recrutador vê o que a pessoa autorizou e quando. É a resposta a "quem autorizou o quê,
 * quando" que a auditoria precisa — e a prova de que revogação e encerramento existem.</p>
 */
public class TalentBankEvent {

	public static final int MAX_DETAIL = 500;

	public enum Action {
		BANK_CREATED,
		BANK_UPDATED,
		VISIBILITY_CHANGED,
		REQUESTED,
		CONFIRMED,
		REVOKED_BY_CANDIDATE,
		CLOSED_BY_RECRUITER,
		ERASED,
		NOTE_UPDATED,
		RESUME_VIEWED
	}

	private final String id;
	private final String bankId;
	private final String enrollmentId;
	private final String actorId;
	private final Action action;
	private final String detail;
	private final Instant createdAt;

	private TalentBankEvent(String id, String bankId, String enrollmentId, String actorId, Action action, String detail,
			Instant createdAt) {
		this.id = id;
		this.bankId = bankId;
		this.enrollmentId = enrollmentId;
		this.actorId = actorId;
		this.action = action;
		this.detail = detail;
		this.createdAt = createdAt;
	}

	public static TalentBankEvent of(String bankId, String enrollmentId, String actorId, Action action, String detail,
			Instant now) {
		return new TalentBankEvent(UUID.randomUUID().toString(), bankId, enrollmentId, actorId, action, clean(detail),
			now);
	}

	public static TalentBankEvent restore(String id, String bankId, String enrollmentId, String actorId, Action action,
			String detail, Instant createdAt) {
		return new TalentBankEvent(id, bankId, enrollmentId, actorId, action, detail, createdAt);
	}

	public String id() {
		return id;
	}

	public String bankId() {
		return bankId;
	}

	public String enrollmentId() {
		return enrollmentId;
	}

	public String actorId() {
		return actorId;
	}

	public Action action() {
		return action;
	}

	public String detail() {
		return detail;
	}

	public Instant createdAt() {
		return createdAt;
	}

	private static String clean(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.length() > MAX_DETAIL ? trimmed.substring(0, MAX_DETAIL) : trimmed;
	}
}
