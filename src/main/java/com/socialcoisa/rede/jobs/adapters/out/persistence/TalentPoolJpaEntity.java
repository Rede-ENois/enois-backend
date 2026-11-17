package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.jobs.domain.TalentPoolOptIn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "talent_pool_opt_ins")
class TalentPoolJpaEntity {

	@Id
	@Column(length = 255)
	private String id;

	@Column(name = "candidate_id", nullable = false, length = 255)
	private String candidateId;

	@Column(name = "company_id", nullable = false, length = 255)
	private String companyId;

	@Column(name = "consent_version", nullable = false, length = 32)
	private String consentVersion;

	@Column(name = "consented_at", nullable = false)
	private Instant consentedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "withdrawing_at")
	private Instant withdrawingAt;

	/** O que a pessoa contou além do perfil: uma linha livre que ela escreveu para os recrutadores. */
	@Column(length = 500)
	private String note;

	protected TalentPoolJpaEntity() {
	}

	static TalentPoolJpaEntity fromDomain(TalentPoolOptIn optIn) {
		TalentPoolJpaEntity e = new TalentPoolJpaEntity();
		e.id = optIn.id();
		e.candidateId = optIn.candidateId();
		e.companyId = optIn.companyId();
		e.consentVersion = optIn.consentVersion();
		e.consentedAt = optIn.consentedAt();
		e.updatedAt = optIn.updatedAt();
		e.withdrawingAt = optIn.withdrawingAt();
		e.note = optIn.note();
		return e;
	}

	TalentPoolOptIn toDomain() {
		return TalentPoolOptIn.restore(id, candidateId, companyId, consentVersion, consentedAt, updatedAt,
			withdrawingAt, note);
	}
}
