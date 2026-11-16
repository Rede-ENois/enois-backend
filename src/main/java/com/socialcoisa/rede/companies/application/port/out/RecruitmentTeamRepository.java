package com.socialcoisa.rede.companies.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.companies.domain.RecruitmentMember;
import com.socialcoisa.rede.companies.domain.RecruitmentRole;

/** Equipe de recrutamento de uma organizacao (a pagina empresarial). */
public interface RecruitmentTeamRepository {

	RecruitmentMember save(RecruitmentMember member);

	Optional<RecruitmentMember> findMember(String companyId, String userId);

	List<RecruitmentMember> membersOf(String companyId);

	/** Troca o papel de um membro. */
	RecruitmentMember updateRole(String companyId, String userId, RecruitmentRole role);

	/** Remove o membro da equipe. */
	void removeMember(String companyId, String userId);
}
