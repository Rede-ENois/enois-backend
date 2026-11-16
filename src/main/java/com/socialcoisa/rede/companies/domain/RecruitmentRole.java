package com.socialcoisa.rede.companies.domain;

/**
 * Papeis da equipe de recrutamento de uma organizacao (a pagina empresarial).
 *
 * <p>Sao um eixo separado do papel de pagina ({@link CompanyRole}): publicar na pagina e
 * conduzir uma selecao nao sao o mesmo trabalho. O papel de recrutamento diz o que a pessoa
 * faz dentro do processo das vagas da organizacao.
 */
public enum RecruitmentRole {
	/** Administrador da organizacao: comanda o recrutamento e mexe na equipe. */
	ORG_ADMIN,
	/** Gestor de recrutamento: configura o processo e coordena a triagem. */
	RECRUITMENT_MANAGER,
	/** Recrutador: tria candidatura e marca entrevista. */
	RECRUITER,
	/** Entrevistador: ve o candidato e conduz a entrevista. */
	INTERVIEWER,
	/** Observador: acompanha o processo sem alterar nada. */
	OBSERVER
}
