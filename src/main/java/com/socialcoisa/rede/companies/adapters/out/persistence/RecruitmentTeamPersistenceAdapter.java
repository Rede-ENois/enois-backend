package com.socialcoisa.rede.companies.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.companies.application.port.out.RecruitmentTeamRepository;
import com.socialcoisa.rede.companies.domain.RecruitmentMember;
import com.socialcoisa.rede.companies.domain.RecruitmentRole;

@Repository
class RecruitmentTeamPersistenceAdapter implements RecruitmentTeamRepository {

	private final SpringDataRecruitmentMemberJpaRepository members;

	RecruitmentTeamPersistenceAdapter(SpringDataRecruitmentMemberJpaRepository members) {
		this.members = members;
	}

	@Override
	public RecruitmentMember save(RecruitmentMember member) {
		return members.save(RecruitmentMemberJpaEntity.fromDomain(member)).toDomain();
	}

	@Override
	public Optional<RecruitmentMember> findMember(String companyId, String userId) {
		return members.findByCompanyIdAndUserId(companyId, userId).map(RecruitmentMemberJpaEntity::toDomain);
	}

	@Override
	public List<RecruitmentMember> membersOf(String companyId) {
		return members.findByCompanyIdOrderByCreatedAtAsc(companyId).stream()
			.map(RecruitmentMemberJpaEntity::toDomain).toList();
	}

	@Override
	public RecruitmentMember updateRole(String companyId, String userId, RecruitmentRole role) {
		RecruitmentMemberJpaEntity entity = members.findByCompanyIdAndUserId(companyId, userId)
			.orElseThrow(() -> new IllegalStateException("membro ausente: " + userId));
		entity.changeRole(role);
		return members.save(entity).toDomain();
	}

	@Override
	public void removeMember(String companyId, String userId) {
		members.findByCompanyIdAndUserId(companyId, userId).ifPresent(members::delete);
	}
}
