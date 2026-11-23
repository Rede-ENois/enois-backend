package com.socialcoisa.rede.talentbank.adapters.out.persistence;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import com.socialcoisa.rede.talentbank.domain.EnrollmentState;
import com.socialcoisa.rede.talentbank.domain.TalentBankEnrollment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "talent_bank_enrollments")
class TalentBankEnrollmentJpaEntity {

	@Id
	@Column(length = 255)
	private String id;

	@Column(name = "bank_id", nullable = false, length = 255)
	private String bankId;

	@Column(name = "candidate_id", nullable = false, length = 255)
	private String candidateId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private EnrollmentState state;

	@Column(name = "consent_version", nullable = false, length = 32)
	private String consentVersion;

	/** Os campos autorizados, separados por virgula — a vírgula é recusada no texto do campo. */
	@Column(name = "shared_fields", nullable = false, length = 300)
	private String sharedFields;

	/** A finalidade da banca no momento do pedido: o texto que a pessoa leu antes de autorizar. */
	@Column(name = "purpose_snapshot", nullable = false, length = 600)
	private String purposeSnapshot;

	@Column(length = 500)
	private String note;

	@Column(name = "requested_at", nullable = false)
	private Instant requestedAt;

	@Column(name = "consented_at")
	private Instant consentedAt;

	@Column(name = "activated_at")
	private Instant activatedAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Column(name = "erased_at")
	private Instant erasedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected TalentBankEnrollmentJpaEntity() {
	}

	static TalentBankEnrollmentJpaEntity fromDomain(TalentBankEnrollment enrollment) {
		TalentBankEnrollmentJpaEntity e = new TalentBankEnrollmentJpaEntity();
		e.id = enrollment.id();
		e.bankId = enrollment.bankId();
		e.candidateId = enrollment.candidateId();
		e.state = enrollment.state();
		e.consentVersion = enrollment.consentVersion();
		e.sharedFields = String.join(",", enrollment.sharedFields());
		e.purposeSnapshot = enrollment.purposeSnapshot();
		e.note = enrollment.note();
		e.requestedAt = enrollment.requestedAt();
		e.consentedAt = enrollment.consentedAt();
		e.activatedAt = enrollment.activatedAt();
		e.revokedAt = enrollment.revokedAt();
		e.closedAt = enrollment.closedAt();
		e.erasedAt = enrollment.erasedAt();
		e.updatedAt = enrollment.updatedAt();
		return e;
	}

	TalentBankEnrollment toDomain() {
		return TalentBankEnrollment.restore(id, bankId, candidateId, state, consentVersion, sharedFields(),
			purposeSnapshot, note, requestedAt, consentedAt, activatedAt, revokedAt, closedAt, erasedAt, updatedAt);
	}

	String getCandidateId() {
		return candidateId;
	}

	private List<String> sharedFields() {
		if (sharedFields == null || sharedFields.isBlank()) {
			return List.of();
		}
		return Arrays.stream(sharedFields.split(","))
			.map(String::trim)
			.filter(value -> !value.isEmpty())
			.toList();
	}
}
