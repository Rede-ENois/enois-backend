package com.socialcoisa.rede.talentbank.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A inscrição de uma pessoa em uma banca: o consentimento, o que ele autorizou e como terminou.
 *
 * <p>Nada é compartilhado antes da confirmação: o pedido fica pendente e o recrutador não enxerga
 * dado nenhum da pessoa. Depois de confirmada, o recrutador vê apenas os campos marcados — e a
 * lista é a prova do consentimento, não uma cópia do perfil.</p>
 *
 * <p>Revogação e encerramento cortam o acesso na hora e não apagam nada: a linha continua guardando
 * o fato do consentimento (e a data do fim), e o expurgo posterior é que remove a prova depois do
 * prazo de guarda.</p>
 */
public class TalentBankEnrollment {

	public static final int MAX_NOTE = 500;

	private final String id;
	private final String bankId;
	private final String candidateId;
	private final EnrollmentState state;
	private final String consentVersion;
	private final List<String> sharedFields;
	private final String purposeSnapshot;
	private final String note;
	private final Instant requestedAt;
	private final Instant consentedAt;
	private final Instant activatedAt;
	private final Instant revokedAt;
	private final Instant closedAt;
	private final Instant erasedAt;
	private final Instant updatedAt;

	private TalentBankEnrollment(String id, String bankId, String candidateId, EnrollmentState state,
			String consentVersion, List<String> sharedFields, String purposeSnapshot, String note,
			Instant requestedAt, Instant consentedAt, Instant activatedAt, Instant revokedAt, Instant closedAt,
			Instant erasedAt, Instant updatedAt) {
		this.id = id;
		this.bankId = bankId;
		this.candidateId = candidateId;
		this.state = state;
		this.consentVersion = consentVersion;
		this.sharedFields = List.copyOf(sharedFields);
		this.purposeSnapshot = purposeSnapshot;
		this.note = note;
		this.requestedAt = requestedAt;
		this.consentedAt = consentedAt;
		this.activatedAt = activatedAt;
		this.revokedAt = revokedAt;
		this.closedAt = closedAt;
		this.erasedAt = erasedAt;
		this.updatedAt = updatedAt;
	}

	/** Pedido novo: nasce pendente, sem liberar acesso nenhum ao recrutador. */
	public static TalentBankEnrollment request(String bankId, String candidateId, String consentVersion,
			List<String> sharedFields, String purposeSnapshot, String note, Instant now) {
		return new TalentBankEnrollment(UUID.randomUUID().toString(), bankId, candidateId, EnrollmentState.PENDING,
			consentVersion, sharedFields, purposeSnapshot, clean(note), now, null, null, null, null, null, now);
	}

	public static TalentBankEnrollment restore(String id, String bankId, String candidateId, EnrollmentState state,
			String consentVersion, List<String> sharedFields, String purposeSnapshot, String note,
			Instant requestedAt, Instant consentedAt, Instant activatedAt, Instant revokedAt, Instant closedAt,
			Instant erasedAt, Instant updatedAt) {
		return new TalentBankEnrollment(id, bankId, candidateId, state, consentVersion, sharedFields,
			purposeSnapshot, note, requestedAt, consentedAt, activatedAt, revokedAt, closedAt, erasedAt, updatedAt);
	}

	/** A confirmação da pessoa: é ela que liga o acesso e fixa a data do consentimento. */
	public TalentBankEnrollment confirm(Instant now) {
		if (state != EnrollmentState.PENDING) {
			throw new IllegalStateException("inscricao nao pendente");
		}
		return new TalentBankEnrollment(id, bankId, candidateId, EnrollmentState.ACTIVE, consentVersion, sharedFields,
			purposeSnapshot, note, requestedAt, now, now, revokedAt, closedAt, erasedAt, now);
	}

	/** Revogação da pessoa: o acesso morre agora; o registro fica para a auditoria. */
	public TalentBankEnrollment revoke(Instant now) {
		if (state != EnrollmentState.PENDING && state != EnrollmentState.ACTIVE) {
			throw new IllegalStateException("inscricao ja encerrada");
		}
		return new TalentBankEnrollment(id, bankId, candidateId, EnrollmentState.REVOKED_BY_CANDIDATE,
			consentVersion, sharedFields, purposeSnapshot, note, requestedAt, consentedAt, activatedAt, now,
			closedAt, erasedAt, now);
	}

	/** Encerramento pelo recrutador: mesmo efeito de acesso, pelo outro lado da relação. */
	public TalentBankEnrollment close(Instant now) {
		if (state != EnrollmentState.ACTIVE) {
			throw new IllegalStateException("inscricao sem acesso ativo");
		}
		return new TalentBankEnrollment(id, bankId, candidateId, EnrollmentState.CLOSED_BY_RECRUITER, consentVersion,
			sharedFields, purposeSnapshot, note, requestedAt, consentedAt, activatedAt, revokedAt, now, erasedAt, now);
	}

	/**
	 * Pedido de novo depois de revogar ou encerrar: a linha é reaproveitada (o par banca/candidato é
	 * único), mas o consentimento é o de HOJE — campos, finalidade e data.
	 */
	public TalentBankEnrollment renew(String consentVersion, List<String> sharedFields, String purposeSnapshot,
			String note, Instant now) {
		if (!state.terminal()) {
			throw new IllegalStateException("inscricao ainda em curso");
		}
		return new TalentBankEnrollment(id, bankId, candidateId, EnrollmentState.PENDING, consentVersion, sharedFields,
			purposeSnapshot, clean(note), now, null, null, null, null, null, now);
	}

	/** O recado da pessoa para o recrutador: não muda o consentimento, então não o renova. */
	public TalentBankEnrollment withNote(String newNote, Instant now) {
		return new TalentBankEnrollment(id, bankId, candidateId, state, consentVersion, sharedFields,
			purposeSnapshot, clean(newNote), requestedAt, consentedAt, activatedAt, revokedAt, closedAt, erasedAt, now);
	}

	/**
	 * Pedido de exclusão: o recado some e a linha fica marcada como apagada. O fato do consentimento
	 * continua, porque é ele que dá sustainação legal à guarda — mas nenhum dado da pessoa sobra.
	 */
	public TalentBankEnrollment erase(Instant now) {
		return new TalentBankEnrollment(id, bankId, candidateId, state, consentVersion, sharedFields,
			purposeSnapshot, null, requestedAt, consentedAt, activatedAt, revokedAt, closedAt, now, now);
	}

	public boolean grantsAccess() {
		return state.grantsAccess();
	}

	public boolean shares(SharedField field) {
		return sharedFields.contains(field.key());
	}

	public String id() {
		return id;
	}

	public String bankId() {
		return bankId;
	}

	public String candidateId() {
		return candidateId;
	}

	public EnrollmentState state() {
		return state;
	}

	public String consentVersion() {
		return consentVersion;
	}

	public List<String> sharedFields() {
		return sharedFields;
	}

	public String purposeSnapshot() {
		return purposeSnapshot;
	}

	public String note() {
		return note;
	}

	public Instant requestedAt() {
		return requestedAt;
	}

	public Instant consentedAt() {
		return consentedAt;
	}

	public Instant activatedAt() {
		return activatedAt;
	}

	public Instant revokedAt() {
		return revokedAt;
	}

	public Instant closedAt() {
		return closedAt;
	}

	public Instant erasedAt() {
		return erasedAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

	private static String clean(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.length() > MAX_NOTE ? trimmed.substring(0, MAX_NOTE) : trimmed;
	}
}
