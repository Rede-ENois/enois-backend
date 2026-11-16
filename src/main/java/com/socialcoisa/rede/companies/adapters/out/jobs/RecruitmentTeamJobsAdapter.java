package com.socialcoisa.rede.companies.adapters.out.jobs;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.companies.application.RecruitmentTeamService;
import com.socialcoisa.rede.jobs.application.port.out.RecruitmentTeamReader;

/**
 * Expoe o servico da equipe de recrutamento a porta do lado de vagas
 * ({@link RecruitmentTeamReader}). O modulo de vagas nunca importa o servico diretamente —
 * a dependencia entre modulos e uma via so, e quem guarda o papel o implementa.
 */
@Component
class RecruitmentTeamJobsAdapter implements RecruitmentTeamReader {

	private final RecruitmentTeamService team;

	RecruitmentTeamJobsAdapter(RecruitmentTeamService team) {
		this.team = team;
	}

	@Override
	public boolean can(String companyId, String userId, com.socialcoisa.rede.companies.domain.RecruitmentCapability capability) {
		return team.can(companyId, userId, capability);
	}
}
