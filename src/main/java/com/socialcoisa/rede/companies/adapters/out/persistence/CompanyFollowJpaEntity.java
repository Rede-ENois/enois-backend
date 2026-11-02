package com.socialcoisa.rede.companies.adapters.out.persistence;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "company_follows")
@IdClass(CompanyFollowJpaEntity.Key.class)
class CompanyFollowJpaEntity {

	@Id
	@Column(name = "company_id")
	private String companyId;

	@Id
	@Column(name = "user_id")
	private String userId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected CompanyFollowJpaEntity() {
	}

	CompanyFollowJpaEntity(String companyId, String userId, Instant createdAt) {
		this.companyId = companyId;
		this.userId = userId;
		this.createdAt = createdAt;
	}

	record Key(String companyId, String userId) implements Serializable {
	}
}
