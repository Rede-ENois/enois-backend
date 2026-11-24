package com.socialcoisa.rede.talentbank.adapters.out.persistence;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import com.socialcoisa.rede.talentbank.domain.TalentBank;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "talent_banks")
class TalentBankJpaEntity {

	@Id
	@Column(length = 255)
	private String id;

	@Column(name = "owner_id", nullable = false, length = 255)
	private String ownerId;

	@Column(nullable = false, length = 120)
	private String title;

	@Column(nullable = false, length = 600)
	private String purpose;

	@Column(length = 1500)
	private String description;

	@Column(name = "handling_conditions", length = 1500)
	private String handlingConditions;

	@Column(name = "focus_area", length = 120)
	private String focusArea;

	/** As skills buscadas, separadas por virgula — a vírgula é recusada no texto da skill. */
	@Column(name = "desired_skills", length = 500)
	private String desiredSkills;

	/** Os campos que o recrutador pede, separados por vírgula. */
	@Column(name = "requested_fields", nullable = false, length = 300)
	private String requestedFields;

	@Column(name = "consent_version", nullable = false, length = 32)
	private String consentVersion;

	@Column(nullable = false)
	private boolean discoverable;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected TalentBankJpaEntity() {
	}

	static TalentBankJpaEntity fromDomain(TalentBank bank) {
		TalentBankJpaEntity e = new TalentBankJpaEntity();
		e.id = bank.id();
		e.ownerId = bank.ownerId();
		e.title = bank.title();
		e.purpose = bank.purpose();
		e.description = bank.description();
		e.handlingConditions = bank.handlingConditions();
		e.focusArea = bank.focusArea();
		e.desiredSkills = bank.desiredSkills().isEmpty() ? null : String.join(",", bank.desiredSkills());
		e.requestedFields = String.join(",", bank.requestedFields());
		e.consentVersion = bank.consentVersion();
		e.discoverable = bank.discoverable();
		e.createdAt = bank.createdAt();
		e.updatedAt = bank.updatedAt();
		return e;
	}

	TalentBank toDomain() {
		return TalentBank.restore(id, ownerId, title, purpose, description, handlingConditions, focusArea,
			desiredSkills(), requestedFields(), consentVersion, discoverable, createdAt, updatedAt);
	}

	private List<String> requestedFields() {
		if (requestedFields == null || requestedFields.isBlank()) {
			return List.of();
		}
		return Arrays.stream(requestedFields.split(","))
			.map(String::trim)
			.filter(value -> !value.isEmpty())
			.toList();
	}

	private List<String> desiredSkills() {
		if (desiredSkills == null || desiredSkills.isBlank()) {
			return List.of();
		}
		return Arrays.stream(desiredSkills.split(","))
			.map(String::trim)
			.filter(value -> !value.isEmpty())
			.toList();
	}
}
