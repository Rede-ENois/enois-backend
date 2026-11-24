package com.socialcoisa.rede.talentbank.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.talentbank.domain.TalentBankEvent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "talent_bank_events")
class TalentBankEventJpaEntity {

	@Id
	@Column(length = 255)
	private String id;

	@Column(name = "bank_id", nullable = false, length = 255)
	private String bankId;

	@Column(name = "enrollment_id", length = 255)
	private String enrollmentId;

	@Column(name = "actor_id", nullable = false, length = 255)
	private String actorId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private TalentBankEvent.Action action;

	@Column(length = 500)
	private String detail;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected TalentBankEventJpaEntity() {
	}

	static TalentBankEventJpaEntity fromDomain(TalentBankEvent event) {
		TalentBankEventJpaEntity e = new TalentBankEventJpaEntity();
		e.id = event.id();
		e.bankId = event.bankId();
		e.enrollmentId = event.enrollmentId();
		e.actorId = event.actorId();
		e.action = event.action();
		e.detail = event.detail();
		e.createdAt = event.createdAt();
		return e;
	}

	TalentBankEvent toDomain() {
		return TalentBankEvent.restore(id, bankId, enrollmentId, actorId, action, detail, createdAt);
	}
}
