package com.socialcoisa.rede.talentbank.domain;

/**
 * O estado da inscrição de uma pessoa em uma banca.
 *
 * <p>As transições são controladas e ficam registradas: quem pediu, quem confirmou, quem revogou.
 * Só {@link #ACTIVE} libera a leitura dos dados autorizados — pendente é um pedido que a pessoa
 * ainda não confirmou, e revogada ou encerrada é acesso encerrado para sempre.</p>
 */
public enum EnrollmentState {

	/** A pessoa pediu a inscrição e ainda não confirmou o consentimento. */
	PENDING,
	/** Consentimento confirmado: o recrutador vê apenas os campos autorizados. */
	ACTIVE,
	/** A pessoa retirou a inscrição. */
	REVOKED_BY_CANDIDATE,
	/** O recrutador encerrou a inscrição. */
	CLOSED_BY_RECRUITER;

	public boolean grantsAccess() {
		return this == ACTIVE;
	}

	public boolean terminal() {
		return this == REVOKED_BY_CANDIDATE || this == CLOSED_BY_RECRUITER;
	}
}
