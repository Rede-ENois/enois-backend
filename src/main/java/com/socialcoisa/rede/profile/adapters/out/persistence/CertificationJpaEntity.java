package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.time.Instant;
import java.time.LocalDate;

import com.socialcoisa.rede.profile.domain.Certification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_certifications", indexes = @Index(name = "idx_user_certifications_user", columnList = "user_id"))
class CertificationJpaEntity {

	@Id
	private String id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String issuer;

	@Column(columnDefinition = "text")
	private String description;

	@Column(name = "issue_date", nullable = false)
	private LocalDate issueDate;

	@Column(name = "expiration_date")
	private LocalDate expirationDate;

	@Column(name = "credential_url", length = 500)
	private String credentialUrl;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected CertificationJpaEntity() {
	}

	static CertificationJpaEntity fromDomain(Certification certification) {
		CertificationJpaEntity e = new CertificationJpaEntity();
		e.id = certification.id();
		e.userId = certification.userId();
		e.name = certification.name();
		e.issuer = certification.issuer();
		e.description = certification.description();
		e.issueDate = certification.issueDate();
		e.expirationDate = certification.expirationDate();
		e.credentialUrl = certification.credentialUrl();
		e.createdAt = certification.createdAt();
		e.updatedAt = certification.updatedAt();
		return e;
	}

	Certification toDomain() {
		return Certification.restore(id, userId, name, issuer, description, issueDate, expirationDate, credentialUrl,
			createdAt, updatedAt);
	}

}
