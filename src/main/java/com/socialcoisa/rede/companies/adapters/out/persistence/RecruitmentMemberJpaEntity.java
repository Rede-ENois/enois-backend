package com.socialcoisa.rede.companies.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.companies.domain.RecruitmentMember;
import com.socialcoisa.rede.companies.domain.RecruitmentRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "recruitment_members")
class RecruitmentMemberJpaEntity {

	@Id
	private String id;

	@Column(name = "company_id", nullable = false)
	private String companyId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private RecruitmentRole role;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected RecruitmentMemberJpaEntity() {
	}

	static RecruitmentMemberJpaEntity fromDomain(RecruitmentMember member) {
		RecruitmentMemberJpaEntity e = new RecruitmentMemberJpaEntity();
		e.id = member.id();
		e.companyId = member.companyId();
		e.userId = member.userId();
		e.role = member.role();
		e.createdAt = member.createdAt();
		return e;
	}

	RecruitmentMember toDomain() {
		return RecruitmentMember.create(id, companyId, userId, role, createdAt);
	}

	void changeRole(RecruitmentRole newRole) {
		this.role = newRole;
	}
}
