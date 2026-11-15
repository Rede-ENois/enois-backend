package com.socialcoisa.rede.companies.domain;

/**
 * O que cada papel da equipe de recrutamento pode fazer sobre as vagas da organizacao.
 *
 * <p>Uma tabela de decisao explicita, como em {@code GroupPermissions}: adicionar papel novo
 * quebra o switch na compilacao, e nenhuma resposta e implicita. Quem nao tem registro na
 * equipe (o visitante) nao pode nada — o dono da pagina vale ORG_ADMIN sem registro.
 */
public final class RecruitmentPermissions {

	private RecruitmentPermissions() {
	}

	public static boolean can(RecruitmentRole storedRole, boolean pageOwner, RecruitmentCapability capability) {
		if (pageOwner) {
			return true; // o dono da pagina comanda o recrutamento dela inteiro
		}
		if (storedRole == null) {
			return false; // quem nao esta na equipe nao ve nem o funil
		}
		return switch (capability) {
			case VIEW_CANDIDATES -> true; // qualquer papel da equipe acompanha o processo
			case TRIAGE -> storedRole == RecruitmentRole.ORG_ADMIN
				|| storedRole == RecruitmentRole.RECRUITMENT_MANAGER || storedRole == RecruitmentRole.RECRUITER;
			case CONFIGURE_PROCESS -> storedRole == RecruitmentRole.ORG_ADMIN
				|| storedRole == RecruitmentRole.RECRUITMENT_MANAGER;
			case MANAGE_INTERVIEWS -> storedRole == RecruitmentRole.ORG_ADMIN
				|| storedRole == RecruitmentRole.RECRUITMENT_MANAGER || storedRole == RecruitmentRole.RECRUITER
				|| storedRole == RecruitmentRole.INTERVIEWER;
			case MANAGE_TEAM -> storedRole == RecruitmentRole.ORG_ADMIN;
		};
	}
}
