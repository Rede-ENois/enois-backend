package com.socialcoisa.rede.jobs.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A candidatura que a pessoa libera, por vontade própria, para uma organização ver sem vaga
 * aberta.
 *
 * <p>O consentimento é o registro inteiro: sem a linha, o candidato não é encontrado por busca
 * nenhuma. A retirada não apaga a linha — marca {@code withdrawingAt}, e a partir dali toda leitura
 * do recrutamento a ignora. O fato de ter liberado (e de ter retirado) continua auditável até o
 * expurgo.
 */
public class TalentPoolOptIn {

	/** A versão do texto de consentimento aceito; muda junto com o texto, nunca antes. */
	public static final String CONSENT_VERSION = "2026-11";

	private static final int MAX_NOTE = 500;

	private final String id;
	private final String candidateId;
	private final String companyId;
	private final String consentVersion;
	private final Instant consentedAt;
	private final Instant updatedAt;
	private final Instant withdrawingAt;
	private final String note;

	private TalentPoolOptIn(String id, String candidateId, String companyId, String consentVersion, Instant consentedAt,
			Instant updatedAt, Instant withdrawingAt, String note) {
		this.id = id;
		this.candidateId = candidateId;
		this.companyId = companyId;
		this.consentVersion = consentVersion;
		this.consentedAt = consentedAt;
		this.updatedAt = updatedAt;
		this.withdrawingAt = withdrawingAt;
		this.note = note;
	}

	/** Liberação nova: já nasce ativa, com o texto de consentimento que a pessoa leu. */
	public static TalentPoolOptIn create(String candidateId, String companyId, Instant now) {
		return new TalentPoolOptIn(UUID.randomUUID().toString(), candidateId, companyId, CONSENT_VERSION, now, now,
			null, null);
	}

	public static TalentPoolOptIn restore(String id, String candidateId, String companyId, String consentVersion,
			Instant consentedAt, Instant updatedAt, Instant withdrawingAt, String note) {
		return new TalentPoolOptIn(id, candidateId, companyId, consentVersion, consentedAt, updatedAt, withdrawingAt,
			note);
	}

	/** O recado para os recrutadores. Não muda o que foi consentido, então não pede consentimento novo. */
	public TalentPoolOptIn withNote(String newNote, Instant now) {
		return new TalentPoolOptIn(id, candidateId, companyId, consentVersion, consentedAt, now, withdrawingAt,
			clean(newNote));
	}

	/** Retirada: idempotente, e a linha só some de fato no expurgo. */
	public TalentPoolOptIn withdraw(Instant now) {
		if (withdrawingAt != null) {
			return this;
		}
		return new TalentPoolOptIn(id, candidateId, companyId, consentVersion, consentedAt, now, now, note);
	}

	/**
	 * Liberar de novo depois de retirar volta a valer com o consentimento de HOJE: a linha é
	 * reaproveitada (o par candidato/organização é único), mas a data e a versão são as do ato novo.
	 */
	public TalentPoolOptIn reopen(Instant now) {
		return new TalentPoolOptIn(id, candidateId, companyId, CONSENT_VERSION, now, now, null, null);
	}

	public boolean active() {
		return withdrawingAt == null;
	}

	private static String clean(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.length() > MAX_NOTE ? trimmed.substring(0, MAX_NOTE) : trimmed;
	}

	public String id() {
		return id;
	}

	public String candidateId() {
		return candidateId;
	}

	public String companyId() {
		return companyId;
	}

	public String consentVersion() {
		return consentVersion;
	}

	public Instant consentedAt() {
		return consentedAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

	public Instant withdrawingAt() {
		return withdrawingAt;
	}

	public String note() {
		return note;
	}
}
