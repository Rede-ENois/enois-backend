package com.socialcoisa.rede.companies.domain;

/**
 * O que a equipe de recrutamento pode pedir sobre uma vaga da organizacao. O lado de vagas
 * enxerga este enum, nunca o dominio de empresas — a dependencia e uma via so.
 */
public enum RecruitmentCapability {
	/** Ver as candidaturas da vaga (tabela, kanban, historico) e a central. */
	VIEW_CANDIDATES,
	/** Mover candidatura entre etapas, reprovar e dar retorno. */
	TRIAGE,
	/** Configurar o fluxo seletivo da vaga (etapas, ordem). */
	CONFIGURE_PROCESS,
	/** Marcar, remarcar e cancelar entrevista da vaga. */
	MANAGE_INTERVIEWS,
	/** Convidar, mudar papel e remover gente da equipe. */
	MANAGE_TEAM
}
